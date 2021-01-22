package org.apache.samza.controller;

//import com.sun.org.apache.regexp.internal.RE;
//import javafx.util.Pair;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.config.Config;
import org.apache.samza.controller.vertical.*;
import org.apache.samza.storage.kv.Entry;
import org.json.simple.JSONObject;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Int;

import java.util.*;
import java.util.function.DoubleBinaryOperator;

//Under development

public class LatencyGuarantor extends StreamSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.LatencyGuarantor.class);
    private long latencyReq, windowReq; //Window requirment is stored as number of timeslot
    private double l_low, l_high; // Check instantDelay  < l and longtermDelay < req
    long migrationInterval;
    private boolean isStarted;
    private Examiner examiner;
    private Map<String, Resource> shrinks = new HashMap<String, Resource>();
    private Map<String, Resource> expands = new HashMap<String, Resource>();
    private long adjustPeriod;
    private long startTime = 0;
    private int phase = 0;
    private final ResourceChecker resourceChecker;
    private final Thread resourceCheckerThread;

    public LatencyGuarantor(Config config){
        super(config);
        latencyReq = config.getLong("streamswitch.requirement.latency", 1000); //Unit: millisecond
        windowReq = config.getLong("streamswitch.requirement.window", 2000) / metricsRetreiveInterval; //Unit: # of time slots
        l_low = config.getDouble("streamswitch.system.l_low", 50); //Unit: millisecond
        l_high = config.getDouble("streamswtich.system.l_high", 100);
        migrationInterval = config.getLong("streamswitch.system.migration_interval", 5000l);
        adjustPeriod = config.getLong("streamswitch.system.delta_time", 600);
        String jobName = config.get("job.name");
        examiner = new Examiner(config, metricsRetreiveInterval, windowReq);
        isStarted = false;
        resourceChecker = new ResourceChecker(jobName);
        this.resourceCheckerThread = new Thread(resourceChecker, "Resource Checker Thread");
    }

    @Override
    public void init(OperatorControllerListener listener, List<String> executors, List<String> substreams) {
        super.init(listener, executors, substreams);
        examiner.init(executorMapping);
        resourceChecker.setListener(listener);
        resourceCheckerThread.start();
    }

    public void initResource(){
//        String giver1 = "000002";
//        String giver2 = "000003";
//        String giver3 = "000004";
//        String taker1 = "000005";
//        shrinks.clear();
//        expands.clear();
//        Resource resource = Resource.newInstance(3000, 4);
//        expands.put(giver1, resource);
//        resource = Resource.newInstance(3000, 4);
//        expands.put(giver2, resource);
//        resource = Resource.newInstance(2000, 4);
//        expands.put(giver3, resource);
//        resource = Resource.newInstance(1000, 4);
//        expands.put(taker1, resource);
//        resourceChecker.startAdjust(shrinks, expands);
    }



    //Return state validity
    private boolean examine(long timeIndex){
        Map<String, Object> metrics = metricsRetriever.retrieveMetrics();
        Map<String, Long> substreamArrived =
                (HashMap<String, Long>) (metrics.get("Arrived"));
        Map<String, Long> substreamProcessed =
                (HashMap<String, Long>) (metrics.get("Processed"));
        Map<String, Boolean> substreamValid =
                (HashMap<String,Boolean>)metrics.getOrDefault("Validity", null);
        Map<String, Double> executorServiceRate =
                (HashMap<String, Double>) (metrics.get("ServiceRate"));
        Map<String, Resource> executorResources =
                (HashMap<String, Resource>) (metrics.get("Resources"));
        Map<String, Double> executorMemUsed =
                (HashMap<String, Double>) (metrics.get("MemUsed"));
        if(executorMemUsed == null || executorMemUsed.size() == 0)
            return false;

        Map<String, Double> executorCpuUsage =
                (HashMap<String, Double>) (metrics.get("CpuUsage"));
        Map<String, Double> executorHeapUsed =
                (HashMap<String, Double>) (metrics.get("HeapUsed"));
        Map<String, Long> pgMajFault = (Map<String, Long>) (metrics.get("PGMajFault"));

        System.out.println("Model, time " + timeIndex  + ", executor heap used: " + executorHeapUsed);
        System.out.println("Model, time " + timeIndex + ", executor pg major fault: " + pgMajFault);

        //Memory usage
        //LOG.info("Metrics size arrived size=" + substreamArrived.size() + " processed size=" + substreamProcessed.size() + " valid size=" + substreamValid.size() + " utilization size=" + executorUtilization.size());
        if(examiner.updateState(timeIndex, substreamArrived, substreamProcessed, substreamValid, executorMapping)){
            examiner.updateExecutorState(timeIndex, executorResources, executorMemUsed, executorCpuUsage, pgMajFault);
            examiner.updateModel(timeIndex, executorServiceRate, executorMapping);
//            examiner.updateResourceState();
            return true;
        }
        return false;
    }

    private int min(int num1, int num2){
        if(num1 < num2)
            return num1;
        else return num2;
    }

//    private boolean winwinExchg(Examiner examiner){
//        LOG.info("win win exchange");
//        Map<String, Integer> cpuState = examiner.resourceInfo.cpuState;
//        Map<String, Integer> memState = examiner.resourceInfo.memState;
//        Map<String, Integer> cpuToMemList = new HashMap<>();
//        Map<String, Integer> memToCpuList = new HashMap<>();
//        int cpuGiver = 0;
//        int memGiver = 0;
//
//        for(String executor: memState.keySet()){
//            Integer memStateExecutor = memState.get(executor);
//            if (memStateExecutor >= 1) {
//                cpuGiver += memStateExecutor;
//                cpuToMemList.put(executor, memStateExecutor);
//            }
//            else if(memStateExecutor < 0) {
//                memGiver += 1;
//                memToCpuList.put(executor, 1);
//            }
//        }
//        if(cpuToMemList.size() == 0 || memToCpuList.size() == 0)
//            return false;
//        int numGiver = min(cpuGiver, memGiver);
//        cpuGiver = 0;
//        memGiver = 0;
//        for (Map.Entry<String, Integer> entry: cpuToMemList.entrySet()){
//            if(cpuGiver == numGiver)
//                break;
//            String executor = entry.getKey();
//            Integer numOfCores = entry.getValue();
//            if (numOfCores + cpuGiver > numGiver)
//                numOfCores = numGiver - cpuGiver;
//
//            Resource target = getTargetResource(examiner, executor, 0, -numOfCores);
//            shrinks.put(executor, target);
//            target = getTargetResource(examiner, executor, 100*numOfCores, -numOfCores);
//            expands.put(executor, target);
//            cpuGiver += numOfCores;
//        }
//
//        for (Map.Entry<String, Integer> entry: memToCpuList.entrySet()){
//            if(memGiver == numGiver)
//                break;
//            String executor = entry.getKey();
//
//            Resource target = getTargetResource(examiner, executor, -100, 0);
//            shrinks.put(executor, target);
//            target = getTargetResource(examiner, executor, -100, 1);
//            expands.put(executor, target);
//            memGiver += 1;
//        }

//        int number = min(cpuToMemList.size(), memToCpuList.size());
//        for(int i = 0; i < number; i ++){
//            String executor = cpuToMemList.get(i);
//            Resource target = getTargetResource(examiner, executor, 0, -1);
//            shrinks.put(executor, target);
//            target = getTargetResource(examiner, executor, 100, -1);
//            expands.put(executor, target);
//        }
//
//        for(int i = 0; i < number; i ++){
//            String executor = memToCpuList.get(i);
//            Resource target = getTargetResource(examiner, executor, -100, 0);
//            shrinks.put(executor, target);
//            target = getTargetResource(examiner, executor, -100, 1);
//            expands.put(executor, target);
//        }

//        LOG.info("Shrink: " + shrinks);
//        LOG.info("expand: " + expands);
//        return true;
//    }

//    private Resource getTargetResource(Examiner examiner, String executor, Integer deltaMem, Integer deltaCpu){
//        Integer targetMem = examiner.state.executorState.get(executor).getMemory() + deltaMem;
//        Integer targetCpu = examiner.resources.get(executor).getVirtualCores() + deltaCpu;
//        return Resource.newInstance(targetMem, targetCpu);
//    }

//    private boolean winMoreExchg(Examiner examiner){
//        return false;
//    }


    private Map<String, Long> largestReminder(Map<String, Double> weights, Long total){
        
    }

    private boolean CPUDiagnose(Examiner examiner){
        Map<String, Double> arrivalInDelay = examiner.model.getArrivalRateInDelay();
        Map<String, Double> cpuUsage = examiner.state.getCPUUsage();
        Map<String, Integer> cpuConfig = examiner.state.getCPUConfig();
        Long totalCPU = 0l;
        for (Map.Entry<String, Integer> entry : cpuConfig.entrySet()){
            totalCPU += entry.getValue();
        }

    }


    private boolean diagnose(Examiner examiner){
        if (CPUDiagnose(examiner)){

        } else (memDiagnose(examiner)){

        }

        return false;
    }


    private boolean diagnose_test(Examiner examiner){
        String container2 = "000002";
        String container3 = "000003";
        String container4 = "000004";
        String container5 = "000005";

        if(phase == 0){
//            Resource target = Resource.newInstance(3000, 6);
//            expands.put(container2, target);
//        } else if (phase == 1){
//            Resource target = Resource.newInstance(900, 4);
//            expands.put(container3, target);
//        } else if (phase == 2){
//            Resource target = Resource.newInstance(800, 4);
//            expands.put(container4, target);
//        } else if (phase == 3){
//            Resource target = Resource.newInstance(500, 5);
//            shrinks.put(container5, target);
        }

        phase ++;
        return true;
    }



    //Main logic:  examine->diagnose->treatment->sleep
    void work(long timeIndex) {

        if(startTime == 0) {
            initResource();
            startTime = 1;
        }
        LOG.info("Examine...");
        //Examine
        boolean stateValidity = examine(timeIndex);
        if (resourceChecker.isSleeping()) {

            //Check is started
           if (!isStarted) {
               LOG.info("Check started...");
               for (int id : examiner.state.substreamStates.keySet()) {
                   if (examiner.state.checkArrivedPositive(id, timeIndex)) {
                       isStarted = true;
                       break;
                   }
               }
           }

           if (timeIndex > 1000 && stateValidity && isStarted) {
               shrinks.clear();
               expands.clear();
               if (diagnose_test(examiner)) {
                   resourceChecker.startAdjust(shrinks, expands);
               }

           }
        }

//        try {
//            Thread.sleep(deltaTime);
//        } catch (InterruptedException e) {
//            LOG.error("Interrupted in job coordinator loop {} ", e);
//            Thread.currentThread().interrupt();
//        }

//
//        if (stateValidity && !isMigrating && isStarted){
//            LOG.info("Diagnose...");
//            //Diagnose
//            Prescription pres = diagnose(examiner);
//            if (pres.migratingSubstreams != null) {
//                //Treatment
//                treat(pres);
//            } else {
//                LOG.info("Nothing to do this time.");
//            }
//        } else {
//            if (!stateValidity) LOG.info("Current examine data is not valid, need to wait until valid");
//            else if (isMigrating) LOG.info("One migration is in process");
//            else LOG.info("Too close to last migration");
//        }
    }

    @Override
    public synchronized void onMigrationExecutorsStopped(){

    }
    @Override
    public void onMigrationCompleted(){
    }

    @Override
    public void onResourceResized(String processorId, String extendedJobModel) {

    }

    @Override
    public void onContainerResized(String processorId, String result) {

    }
}
