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

public class VerticalScaling extends StreamSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.LatencyGuarantor.class);
    private long latencyReq, windowReq; //Window requirment is stored as number of timeslot
    private double l_low, l_high; // Check instantDelay  < l and longtermDelay < req
    long migrationInterval;
    private boolean isStarted;
    private Examiner examiner;
    private Map<String, Resource> shrinks = new HashMap<String, Resource>();
    private Map<String, Resource> expands = new HashMap<String, Resource>();
    private long cpuScalingInterval = 0, memScalingInterval = 60;
    private long cpuScalingTime, memScalingTime = 0l;
    private boolean memIsFly;
    private String flyInstance = null;
    private long startTime = 0;
    private int phase = 0;
    private final ResourceChecker resourceChecker;
    private final Thread resourceCheckerThread;

    public VerticalScaling(Config config){
        super(config);
        latencyReq = config.getLong("streamswitch.requirement.latency", 1000); //Unit: millisecond
        windowReq = config.getLong("streamswitch.requirement.window", 2000) / metricsRetreiveInterval; //Unit: # of time slots
        l_low = config.getDouble("streamswitch.system.l_low", 50); //Unit: millisecond
        l_high = config.getDouble("streamswtich.system.l_high", 100);
        migrationInterval = config.getLong("streamswitch.system.migration_interval", 5000l);
//        adjustPeriod = config.getLong("streamswitch.system.delta_time", 600);
        String jobName = config.get("job.name");
        examiner = new Examiner(config, metricsRetreiveInterval, windowReq);
        isStarted = false;
        memIsFly = false;
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
        String giver1 = "000002";
        String giver2 = "000003";
        String giver3 = "000004";
        String taker1 = "000005";
        shrinks.clear();
        expands.clear();
//        Resource resource = Resource.newInstance(1000, 2);
//        shrinks.put(giver1, resource);
//        resource = Resource.newInstance(1000, 2);
//        shrinks.put(giver2, resource);
//        resource = Resource.newInstance(1000, 2);
//        shrinks.put(giver3, resource);
//        resource = Resource.newInstance(1400, 2);
//        shrinks.put(taker1, resource);
        resourceChecker.startAdjust(shrinks, expands);
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

//        System.out.println("Model, time " + timeIndex  + ", executor heap used: " + executorHeapUsed);
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

    private Resource getTargetResource(Examiner examiner, String executor, Integer deltaMem, Integer deltaCpu){
        Integer targetMem = examiner.state.getMemConfig().get(executor) + deltaMem;
        Integer targetCpu = examiner.state.getCPUConfig().get(executor) + deltaCpu;
        return Resource.newInstance(targetMem, targetCpu);
    }


    private Map<String, Integer> largestReminder(Map<String, Double> weights, int total){
        double sumWeights = 0.0;
        for(Double weight : weights.values()){
            sumWeights+=weight;
        }
        double share = total/sumWeights;
        Map<String, Integer> quotas = new HashMap<>();
        Map<String, Double> reminders = new HashMap<>();
        long totalTemp = total;
        for(Map.Entry<String, Double> entry : weights.entrySet()){
            String key = entry.getKey();
            double quotasDouble = entry.getValue() * share;
            int quotaLong = (int) Math.floor(quotasDouble);
            if(quotaLong == 0) {
                quotaLong += 1;
            } else {
                reminders.put(key, quotasDouble - quotaLong);
            }
            quotas.put(key, quotaLong);
            totalTemp -= quotaLong;
        }

        while (totalTemp < 0){
            double largestRatio = 0.0;
            String unlucky = null;
            for(Map.Entry<String, Double> entry : reminders.entrySet()){
                String node = entry.getKey();
                if(quotas.get(node) <= 1)
                    continue;
                double ratio = quotas.get(node) / weights.get(node);
                if (ratio > largestRatio){
                    largestRatio = ratio;
                    unlucky = node;
                }
            }
            if(unlucky == null){
                LOG.info("Error on LargestReminder");
                return null;
            }
            quotas.put(unlucky, quotas.get(unlucky) - 1);
            totalTemp += 1;
        }


        while(totalTemp > 0){
            Double largestReminder = 0.0;
            String lucky = null;
            for(Map.Entry<String, Double> entry : reminders.entrySet()){
                if(entry.getValue() >= largestReminder){
                    largestReminder = entry.getValue();
                    lucky = entry.getKey();
                }
            }
            if (lucky != null){
                quotas.put(lucky, quotas.get(lucky) + 1);
                reminders.remove(lucky);
            } else{
                LOG.info("Wrong Largest Reminder Algorithm");
                return null;
            }
            totalTemp--;
        }
        return quotas;
    }

    private Map<String, Integer> largestLatency(Map<String, Double> weights, int total, Map<String, Double> latencies){
        double sumWeights = 0.0;
        for(Double weight : weights.values()){
            sumWeights+=weight;
        }
        double share = total/sumWeights;
        Map<String, Integer> quotas = new HashMap<>();
        long totalTemp = total;
        for(Map.Entry<String, Double> entry : weights.entrySet()){
            String key = entry.getKey();
            double quotasDouble = entry.getValue() * share;
            int quotaLong = (int) Math.floor(quotasDouble);
            if(quotaLong == 0)
                quotaLong += 1;
            quotas.put(key, quotaLong);
            totalTemp -= quotaLong;
        }

        while (totalTemp < 0){
            double largestRatio = 0.0;
            String unlucky = null;
            for(Map.Entry<String, Double> entry : latencies.entrySet()){
                String node = entry.getKey();
                if(quotas.get(node) <= 1)
                    continue;
                double ratio = quotas.get(node) / weights.get(node);
                if (ratio > largestRatio){
                    largestRatio = ratio;
                    unlucky = node;
                }
            }
            if(unlucky == null){
                LOG.info("Error on LargestReminder");
                return null;
            }
            quotas.put(unlucky, quotas.get(unlucky) - 1);
            totalTemp += 1;
        }

        Map<String, Double> copyLatencies = new HashMap<>();
        copyLatencies.putAll(latencies);
        while(totalTemp > 0){
            Double largestLatencies = 0.0;
            String lucky = null;
            for(Map.Entry<String, Double> entry : copyLatencies.entrySet()){
                if(entry.getValue() >= largestLatencies){
                    largestLatencies = entry.getValue();
                    lucky = entry.getKey();
                }
            }
            if (lucky != null){
                quotas.put(lucky, quotas.get(lucky) + 1);
                copyLatencies.remove(lucky);
            } else{
                LOG.info("Wrong Largest Latency Algorithm");
                return null;
            }
            totalTemp--;
        }
        return quotas;
    }

    private int getTotalCPU(Examiner examiner){
        int total = examiner.state.getTotalCPU();
        if (total == 0) {
            examiner.state.updateTotalResources();
            total = examiner.state.getTotalCPU();
        }
        return total;
    }

    private boolean CPUDiagnose(Examiner examiner){
//        System.out.println("Start Diagnose CPU");
        Map<String, Double> arrivalInDelay = examiner.model.getArrivalRateInDelay();
        Map<String, Integer> cpuConfig = examiner.state.getCPUConfig();
        int totalCPU = getTotalCPU(examiner);
        if (totalCPU < cpuConfig.size()){
            LOG.info("Total CPU size is smaller than the number of instances");
            return false;
        }
//        Map<String, Integer> cpuQuotas = largestReminder(arrivalInDelay, totalCPU);
        Map<String, Integer> cpuQuotas = largestLatency(arrivalInDelay, totalCPU, examiner.model.getInstantDelay());
        System.out.println(cpuQuotas);
        boolean flag = false;
        for (Map.Entry<String, Integer> entry : cpuQuotas.entrySet()){
            String executor = entry.getKey();
            int newQuota = entry.getValue();
            int oldQuota = cpuConfig.get(executor);
            if (newQuota > oldQuota){
                flag = true;
                Resource target = getTargetResource(examiner, entry.getKey(), 0, newQuota - oldQuota);
                expands.put(executor, target);
            } else if(newQuota < oldQuota){
                flag = true;
                Resource target = getTargetResource(examiner, entry.getKey(), 0, newQuota - oldQuota);
                shrinks.put(executor, target);
            }
        }
        return flag;
    }

    private boolean memDiagnose(Examiner examiner){
        String maxLatencyNode = null;
        double maxLatency = 0.0;
        Map<String, Double> latencies = examiner.model.getInstantDelay();
        Map<String, Double> validRates = examiner.model.validRate;
        for(Map.Entry<String, Double> entry : latencies.entrySet()){
            String node = entry.getKey();
            double latency = entry.getValue();
            if(latency > maxLatency){
                maxLatency = latency;
                maxLatencyNode = node;
            }
        }
        if (maxLatencyNode == null)
            return false;

        double maxNodeValidRate = 0.0;
        if(validRates.containsKey(maxLatencyNode)){
            maxNodeValidRate = validRates.get(maxLatencyNode);
        } else {
            LOG.info("No node: " + maxLatencyNode + " in valid rates: " + validRates);
        }
        if (maxNodeValidRate >= 0.9) {
            return false;
        }

        String giverNode = null;
        double giverRate = 0.0;
        validRates.remove(maxLatencyNode);
        for (Map.Entry<String, Double> entry : validRates.entrySet()){
            double rate = entry.getValue();
            String node = entry.getKey();
            if(examiner.state.getMemConfig().get(node) <= 600){
                continue;
            }
            if(rate >= giverRate){
                giverNode = node;
                giverRate = rate;
            }
        }

        if(giverRate < maxNodeValidRate) {
            return false;
        }

        if(giverNode == null)
            return false;
        System.out.println(maxLatency);
        System.out.println(giverNode);
        Resource resource1 = getTargetResource(examiner, maxLatencyNode, 30, 0);
        expands.put(maxLatencyNode, resource1);
        Resource resource2 = getTargetResource(examiner, giverNode, -30, 0);
        shrinks.put(giverNode, resource2);
        flyInstance = maxLatencyNode;
        return true;
    }

    private void checkMemDone(Examiner examiner){
        int configMem = examiner.state.getMemConfig().get(flyInstance);
        double usedMem = examiner.state.getUsedMem(flyInstance);
        if(configMem - usedMem < 15)
            memIsFly = false;
    }


    private boolean diagnose(Examiner examiner, long timeIndex) {
//        System.out.println("timeIndex: " + timeIndex + ", Scaling Time: " + cpuScalingTime);
//        if (((timeIndex - cpuScalingTime) > cpuScalingInterval) && CPUDiagnose(examiner)) {
//            cpuScalingTime = timeIndex;
//            return true;
//        }
//        if (memIsFly)
//            checkMemDone(examiner);
//        else if (memDiagnose(examiner)){
//            memIsFly = true;
//            return true;
//        }
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

//           System.out.println("state: "+stateValidity + ", is start:" + isStarted);
            if (timeIndex > 10 && stateValidity && isStarted) {
                shrinks.clear();
                expands.clear();
                if (diagnose(examiner, timeIndex)) {
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
