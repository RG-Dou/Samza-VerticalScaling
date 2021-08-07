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
import java.util.Random;

import java.util.*;
import java.util.function.DoubleBinaryOperator;

//Under development

public class SimplePolicy extends StreamSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.LatencyGuarantor.class);

    private long currentTimeIndex;

    private boolean isStarted;
    private Examiner examiner;
    private Map<String, Resource> shrinks = new HashMap<String, Resource>();
    private Map<String, Resource> expands = new HashMap<String, Resource>();
    private final ResourceChecker resourceChecker;
    private final Thread resourceCheckerThread;
    //LB,VS-CPU,VS-Both
    private final String policy;
    private boolean one_time = false;
    private long startTime = 0;
    private Random rnd;

    public SimplePolicy(Config config){
        super(config);
        String jobName = config.get("job.name");
        examiner = new Examiner(config, metricsRetreiveInterval);
        isStarted = false;
        resourceChecker = new ResourceChecker(jobName);
        this.resourceCheckerThread = new Thread(resourceChecker, "Resource Checker Thread");
        policy = config.get("simple.policy", "LB");
        currentTimeIndex = 0;
    }

    @Override
    public void init(OperatorControllerListener listener, List<String> executors, List<String> substreams) {
        super.init(listener, executors, substreams);
        examiner.init(executorMapping);
        resourceChecker.setListener(listener);
        resourceCheckerThread.start();
    }

    public void initResourcesMain(){
        shrinks.clear();
        expands.clear();
        Resource resource = Resource.newInstance(3000, 3);
        expands.put("000003", resource);
        resourceChecker.startAdjust(shrinks, expands);
    }

    //Return state validity
    private boolean examine(long timeIndex){
        Map<String, Object> metrics = metricsRetriever.retrieveMetrics();
        long index = adjustTimeIndex();
        if(index > currentTimeIndex)
            currentTimeIndex = (index + currentTimeIndex)/2;
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
        System.out.println("Model, time " + currentTimeIndex + ", executor pg major fault: " + pgMajFault);

        //Memory usage
        //LOG.info("Metrics size arrived size=" + substreamArrived.size() + " processed size=" + substreamProcessed.size() + " valid size=" + substreamValid.size() + " utilization size=" + executorUtilization.size());
        if(examiner.updateState(currentTimeIndex, substreamArrived, substreamProcessed, substreamValid, executorMapping)){
            examiner.updateExecutorState(currentTimeIndex, executorResources, executorMemUsed, executorCpuUsage, pgMajFault);
            examiner.updateModel(currentTimeIndex, executorServiceRate, executorMapping);
//            examiner.updateResourceState();
            return true;
        }
        return false;
    }

    private Resource getTargetResource(Examiner examiner, String executor, Integer deltaMem, Integer deltaCpu){
        Integer targetMem = examiner.state.getMemConfig().get(executor) + deltaMem;
        Integer targetCpu = examiner.state.getCPUConfig().get(executor) + deltaCpu;
        return Resource.newInstance(targetMem, targetCpu);
    }

    private void load_balance(Examiner examiner, String hot){
        Map<String, List<String>> oldMapping = examiner.state.executorMapping;
        List<String> hotPartitions = oldMapping.get(hot);
        List<String> lightPartitions = new ArrayList<String>();
        String light = "";
        for (Map.Entry<String, List<String>> entry : oldMapping.entrySet()){
            if (!entry.getKey().equals(hot)) {
                light = entry.getKey();
                lightPartitions = entry.getValue();
            }
        }
        for (int i = 0; i < 1; i ++){
            String partition = hotPartitions.remove(0);
            lightPartitions.add(partition);
        }
        Map<String, List<String>> newMapping = new HashMap<String, List<String>>();
        newMapping.put(hot, hotPartitions);
        newMapping.put(light, lightPartitions);
        System.out.println("newMapping: " + newMapping);
        listener.remap(newMapping);
    }

    private void vertical_CPU(Examiner examiner, String hot){
        Resource hotResource = getTargetResource(examiner, hot, 0, 1);
        expands.put(hot, hotResource);
        String light = "";
        for (Map.Entry<String, List<String>> entry : examiner.state.executorMapping.entrySet()){
            if (!entry.getKey().equals(hot)) {
                light = entry.getKey();
            }
        }
        Resource lightResource = getTargetResource(examiner, light, 0, -1);
        shrinks.put(light, lightResource);
        System.out.println("newMapping: " + hot + hotResource + light + lightResource);
    }

    private void vertical_both(Examiner examiner, String hot){
        Resource hotResource = getTargetResource(examiner, hot, 2000, 1);
        expands.put(hot, hotResource);
        String light = "";
        for (Map.Entry<String, List<String>> entry : examiner.state.executorMapping.entrySet()){
            if (!entry.getKey().equals(hot)) {
                light = entry.getKey();
            }
        }
        Resource lightResource = getTargetResource(examiner, light, -2000, -1);
        shrinks.put(light, lightResource);
        System.out.println("newMapping: " + hot + hotResource + light + lightResource);

    }

    private boolean diagnose(Examiner examiner){
        Map<String, Double> latencies = examiner.model.getInstantDelay();
        for (Map.Entry<String, Double> entry : latencies.entrySet()){
            double latency = entry.getValue();
            if (latency > 10.0 && !one_time){
                switch (policy) {
                    case "LB":
                        load_balance(examiner, entry.getKey());
                        break;
                    case "VS-CPU":
                        vertical_CPU(examiner, entry.getKey());
                        break;
                    case "VS-Both":
                        vertical_both(examiner, entry.getKey());
                        break;
                }
                one_time = true;
                return true;
            }
        }
        return false;
    }


    //Main logic:  examine->diagnose->treatment->sleep
    void work(long timeIndex) {
        LOG.info("Examine...");
        //Examine
        currentTimeIndex = timeIndex;
        boolean stateValidity = examine(timeIndex);

        if(startTime == 0) {
            startTime = 1;
            initResourcesMain();
        }
        if (resourceChecker.isSleeping()) {

            //Check is started
            if (!isStarted) {
                LOG.info("Check started...");
                for (int id : examiner.state.substreamStates.keySet()) {
                    if (examiner.state.checkArrivedPositive(id, currentTimeIndex)) {
                        isStarted = true;
                        break;
                    }
                }
            }

//           System.out.println("state: "+stateValidity + ", is start:" + isStarted);
            if (currentTimeIndex > 1250 && stateValidity && isStarted) {
                shrinks.clear();
                expands.clear();
                if (diagnose(examiner)) {
//                if (diagnose_test(examiner)) {
                    resourceChecker.startAdjust(shrinks, expands);
                }

            }
        }
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
