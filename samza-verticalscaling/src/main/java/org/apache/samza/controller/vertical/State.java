package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.config.Config;
import org.apache.samza.config.MapConfig;
import org.apache.samza.storage.kv.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class State {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.State.class);
    private Map<Long, Map<Integer, List<Integer>>> mappings;
    public Map<Integer, SubstreamState> substreamStates;
    public ExecutorState executorState;
    private long lastValidTimeIndex;
    private long currentTimeIndex;
    final long metricsRetreiveInterval, winRegression, winPGFault;
    public Map<String, List<String>> executorMapping;
    private final long winMetrics;
    private final long maxWinSize;
    private final double decayFactor;

    private final long deltaThreshold;

    public State(Config config, long metricsRetreiveInterval) {
        this.metricsRetreiveInterval = metricsRetreiveInterval;
        this.winRegression = config.getLong("verticalscaling.window.regression", 2000) / metricsRetreiveInterval; //Unit: # of time slots;
        this.winMetrics = config.getLong("verticalscaling.window.metrics", 100)/metricsRetreiveInterval;
        this.winPGFault = config.getLong("verticalscaling.window.pgfault", 2000)/metricsRetreiveInterval;
        decayFactor = config.getDouble("streamswitch.system.decayfactor", 0.875);
        deltaThreshold = config.getLong("verticalscaling.delta.threshold", 100);
        this.maxWinSize = Math.max(winPGFault, winRegression);

        currentTimeIndex = 0;
        lastValidTimeIndex = 0;
        mappings = new HashMap<>();
        substreamStates = new HashMap<>();
        executorState = new ExecutorState(winPGFault, decayFactor);
    }

    public int substreamIdFromStringToInt(String partition){
        return Integer.parseInt(partition.substring(partition.indexOf(' ') + 1));
    }
    public int executorIdFromStringToInt(String executor){
        return Integer.parseInt(executor);
    }

    public void init(Map<String, List<String>> executorMapping){
        LOG.info("Initialize time point 0...");
        this.executorMapping = executorMapping;
        for (String executor : executorMapping.keySet()) {
            for (String substream : executorMapping.get(executor)) {
                SubstreamState substreamState = new SubstreamState();
                substreamState.init();
                substreamStates.put(substreamIdFromStringToInt(substream), substreamState);
            }
        }
    }

    protected long getTimepoint(long timeIndex){
        return timeIndex * metricsRetreiveInterval;
    }

    protected Map<Integer, List<Integer>> getMapping(long n){
        return mappings.getOrDefault(n, null);
    }
    public long getSubstreamArrived(Integer substream, long n){
        long arrived = 0;
        if(substreamStates.containsKey(substream)){
            arrived = substreamStates.get(substream).arrived.getOrDefault(n, 0l);
        }
        return arrived;
    }

    public double getSubstreamSmoothArrived(Integer substream, long n){
        double arrived = 0.0;
        if(substreamStates.containsKey(substream)){
            arrived = substreamStates.get(substream).arrivedSmooth.getOrDefault(n, 0.0);
        }
        return arrived;
    }

    public long getFirstIndex(int substream, long timeIndex){
        long index = 0;
        if(substreamStates.containsKey(substream)){
            index = substreamStates.get(substream).firstIndex.getOrDefault(timeIndex, 0l);
        }
        return index;
    }

    public long getEndIndex(int substream, long timeIndex){
        long index = 0;
        if(substreamStates.containsKey(substream)){
            index = substreamStates.get(substream).endIndex.getOrDefault(timeIndex, 0l);
        }
        return index;
    }

    public long getSubstreamCompleted(Integer substream, long n){
        long completed = 0;
        if(substreamStates.containsKey(substream)){
            completed = substreamStates.get(substream).completed.getOrDefault(n, 0l);
        }
        return completed;
    }


    public long getSubstreamLatency(Integer substream, long timeIndex){
        long latency = 0;
        if(substreamStates.containsKey(substream)){
            latency = substreamStates.get(substream).totalLatency.getOrDefault(timeIndex, 0l);
        }
        return latency;
    }

    public void drop(long timeIndex, Map<String, Boolean> substreamValid) {
        long totalSize = 0;

        //Drop arrived

        for(String substream: substreamValid.keySet()) {
            SubstreamState substreamState = substreamStates.get(substreamIdFromStringToInt(substream));
            if (substreamValid.get(substream)) {
                long remainedIndex = substreamState.remainedIndex;
                while (remainedIndex < substreamState.arrivedIndex - 1 && remainedIndex < timeIndex - maxWinSize) {
//                            substreamState.arrived.remove(remainedIndex);
                    remainedIndex++;
                }
                substreamState.remainedIndex = remainedIndex;
            }
        }
        //Debug
        for (int substream : substreamStates.keySet()) {
            SubstreamState substreamState = substreamStates.get(substream);
            totalSize += substreamState.arrivedIndex - substreamState.remainedIndex + 1;
        }

        //Drop completed, mappings. These are fixed window

        //Drop mappings
        List<Long> removeIndex = new LinkedList<>();
        for(long index:mappings.keySet()){
            if(index < timeIndex - maxWinSize - 1){
                removeIndex.add(index);
            }
        }
        for(long index:removeIndex){
            mappings.remove(index);
        }
        removeIndex.clear();
//        if (checkValidity(substreamValid)) {
//            for (long index = lastValidTimeIndex + 1; index <= timeIndex; index++) {
//                for (String executor : executorMapping.keySet()) {
//                    for (String substream : executorMapping.get(executor)) {
//                        //Drop completed
//                        if (substreamStates.get(substreamIdFromStringToInt(substream)).completed.containsKey(index - maxWinSize - 1)) {
//                            substreamStates.get(substreamIdFromStringToInt(substream)).completed.remove(index - maxWinSize - 1);
//                        }
//                    }
//                }
//
//            }
//            lastValidTimeIndex = timeIndex;
//        }

        LOG.info("Useless state dropped, current arrived size: " + totalSize + " mapping size: " + mappings.size());
    }

    public boolean checkValidity(Map<String, Boolean> substreamValid){
        if(substreamValid == null)return false;

        //Current we don't haven enough time slot to calculate model
        if(currentTimeIndex < 20){
            LOG.info("Current time slots number is smaller than beta, not valid");
            return false;
        }
        //Substream Metrics Valid
        for(String executor: executorMapping.keySet()) {
            for (String substream : executorMapping.get(executor)) {
                if (!substreamValid.containsKey(substream) || !substreamValid.get(substream)) {
                    LOG.info(substream + "'s metrics is not valid");
                    return false;
                }
            }
        }

        return true;
    }

    //Only called when time n is valid, also update substreamLastValid
    private void calibrateSubstreamArrived(int substream, long timeIndex){
        if(getSubstreamArrived(substream, timeIndex) == 0)
            return;
        SubstreamState substreamState = substreamStates.get(substream);
        long n0 = substreamState.lastValidArrivedIndex;
        substreamState.lastValidArrivedIndex = timeIndex;
        if(n0 < timeIndex - 1) {
            //LOG.info("Calibrate state for " + substream + " from time=" + n0);
            long a0 = getSubstreamArrived(substream, n0);
            long a1 = getSubstreamArrived(substream, timeIndex);
            for (long i = n0 + 1; i < timeIndex; i++) {
                long ai = (a1 - a0) * (i - n0) / (timeIndex - n0) + a0;
                substreamState.arrived.put(i, ai);
//                if(substream == 3)
//                    System.out.println("timeIndex: " + i + ", arrived:" + ai);
            }
        }
        if(n0 < timeIndex){
            for (long i = n0 + 1; i <= timeIndex; i++) {
                double old = substreamState.arrivedSmooth.get(i - 1);
                double newOne = old * decayFactor + substreamState.arrived.get(i) * (1 - decayFactor);
                substreamState.arrivedSmooth.put(i, newOne);
//                if(substream == 3)
//                    System.out.println("timeIndex: " + i + ", smooth arrived:"+ newOne);
            }
        }
    }


    //Only called when time n is valid, also update substreamLastValid
    private void calibrateSubstreamCompleted(int substream, long timeIndex){
        if(getSubstreamCompleted(substream, timeIndex) == 0)
            return;
        SubstreamState substreamState = substreamStates.get(substream);
        long n0 = substreamState.lastValidCompletedIndex;
        substreamState.lastValidCompletedIndex = timeIndex;
        if(n0 < timeIndex - 1) {
            //LOG.info("Calibrate state for " + substream + " from time=" + n0);
            long c0 = getSubstreamCompleted(substream, n0);
            long c1 = getSubstreamCompleted(substream, timeIndex);
            for (long i = n0 + 1; i < timeIndex; i++) {
                long ci = (c1 - c0) * (i - n0) / (timeIndex - n0) + c0;
                substreamState.completed.put(i, ci);
            }
        }
        //Calculate total latency here
        for(long index = n0 + 1; index <= timeIndex; index++){
            substreamState.calculateLatency(index, winMetrics);
//            calculateSubstreamLatency(substream, index);
        }
    }

    //Calibrate whole state including mappings and utilizations
    public void calibrate(Map<String, Boolean> substreamValid){

        //Calibrate mappings
        if(mappings.containsKey(currentTimeIndex)){
            for(long t = currentTimeIndex - 1; t >= 0 && t >= currentTimeIndex - maxWinSize - 1; t--){
                if(!mappings.containsKey(t)){
                    mappings.put(t, mappings.get(currentTimeIndex));
                }else{
                    break;
                }
            }
        }

        //Calibrate substreams' state
        if(substreamValid == null)return ;
        for(String substream: substreamValid.keySet()){
            if (substreamValid.get(substream)){
                calibrateSubstreamArrived(substreamIdFromStringToInt(substream), currentTimeIndex);
                calibrateSubstreamCompleted(substreamIdFromStringToInt(substream), currentTimeIndex);
            }
            else {
                ;
//                System.out.println("invalid substream: " + substreamValid);
            }
        }
    }

    public void insert(long timeIndex, Map<String, Long> substreamArrived,
                        Map<String, Long> substreamProcessed,
                        Map<String, List<String>> executorMapping) { //Normal update
        LOG.info("Debugging, metrics retrieved data, time: " + timeIndex + " substreamArrived: "+ substreamArrived + " substreamProcessed: "+ substreamProcessed + " assignment: " + executorMapping);

        currentTimeIndex = timeIndex;

//        System.out.println("Model, time " + timeIndex  + " , executors arrived: " + substreamArrived);
        HashMap<Integer, List<Integer>> mapping = new HashMap<>();
        for(String executor: executorMapping.keySet()) {
            List<Integer> partitions = new LinkedList<>();
            for (String partitionId : executorMapping.get(executor)) {
                //drg
                if(substreamArrived.containsKey(partitionId) && substreamProcessed.containsKey(partitionId)){
                    partitions.add(substreamIdFromStringToInt(partitionId));
                }

//                        partitions.add(substreamIdFromStringToInt(partitionId));
            }
            //drg
            if(partitions.size() > 0)
                mapping.put(executorIdFromStringToInt(executor), partitions);
//                    mapping.put(executorIdFromStringToInt(executor), partitions);
        }
        if(mapping.size() != executorMapping.size())
            System.out.println("Mapping is: " + mapping);
        mappings.put(currentTimeIndex, mapping);

//                LOG.info("Current time " + timeIndex);

        for(String substream: substreamArrived.keySet()){
            SubstreamState subState = substreamStates.get(substreamIdFromStringToInt(substream));
            long deltaT = currentTimeIndex - subState.lastValidArrivedIndex;
            long tuples = substreamArrived.get(substream) - subState.arrived.get(subState.lastValidArrivedIndex);
            if (tuples / deltaT > deltaThreshold)
                subState.arrived.put(currentTimeIndex, substreamArrived.get(substream));
        }
        for(String substream: substreamProcessed.keySet()){
            SubstreamState subState = substreamStates.get(substreamIdFromStringToInt(substream));
//            if(subState.completed.get(subState.lastValidCompletedIndex) < substreamProcessed.get(substream)) {
            subState.completed.put(currentTimeIndex, substreamProcessed.get(substream));
//            }
        }
    }

    public long getCurrentTimeIndex() {
        return currentTimeIndex;
    }

    public boolean checkArrivedPositive(int id, long timeIndex){
        return (substreamStates.get(id).arrived.containsKey(timeIndex) && substreamStates.get(id).arrived.get(timeIndex) != null && substreamStates.get(id).arrived.get(timeIndex) > 0);
    }

//    public Map<String, Double> getCPUUsage(){
//        return executorState.cpuUsage;
//    }

    public Map<String, Integer> getCPUConfig(){
        return executorState.configCpu;
    }

    public Map<String, Integer> getMemConfig(){
        return executorState.configMem;
    }

    public double getCpuUsage(long timeIndex, String executor){
        double usage = 0.0;
        if(executorState.cpuUsages.containsKey(timeIndex))
            return executorState.cpuUsages.get(timeIndex).getOrDefault(executor, 0.0);
        return usage;
    }

    public double getUsedMem(String executor){
        return executorState.usedMem.getOrDefault(executor, 0.0);
    }

    public long getPGFault(long timeIndex, String executor){
        long pgFault = 0l;
        if(executorState.cpuUsages.containsKey(timeIndex))
            return executorState.pgMajFault.get(timeIndex).getOrDefault(executor, 0l);
        return pgFault;
    }

    public int getTotalCPU(){
        return executorState.totalCPU;
    }

    public void updateTotalResources(){
        int totalCPU = 0, totalMemory = 0;
        for (Map.Entry<String, Integer> entry : executorState.configCpu.entrySet()){
            totalCPU += entry.getValue();
        }
        for (Map.Entry<String, Integer> entry : executorState.configMem.entrySet()){
            totalMemory += entry.getValue();
        }
        executorState.totalCPU = totalCPU;
        executorState.totalMem = totalMemory;
    }

    public double getCurrentPageFault(String executor){
        return executorState.currentPGFaults.getOrDefault(executor, 0.0);
    }

    public double getCurrentCpuUsage(String executor){
        return executorState.currentCpuUsages.getOrDefault(executor, 0.0);
    }

}


class SubstreamState{
    public Map<Long, Long> arrived, completed; //Instead of actual time, use the n-th time point as key
    public long lastValidArrivedIndex;    //Last valid state time point
    public long lastValidCompletedIndex;    //Last valid state time point
    //For calculate instant delay
    public Map<Long, Long> totalLatency;
    public Map<Long, Long> firstIndex, endIndex;
    public Map<Long, Double> arrivedSmooth;
    public long arrivedIndex, remainedIndex;
    public SubstreamState(){
        arrived = new HashMap<>();
        completed = new HashMap<>();
        totalLatency = new HashMap<>();
        firstIndex = new HashMap<>();
        endIndex = new HashMap<>();
        arrivedSmooth = new HashMap<>();
    }

    public void init(){
        arrived.put(0l, 0l);
        completed.put(0l, 0l);
        arrivedSmooth.put(0l, 0.0);
        lastValidArrivedIndex = 0l;
        lastValidCompletedIndex = 0l;
        arrivedIndex = 1l;
        remainedIndex = 0l;
    }
    //Calculate the total latency in new slot. Drop useless data during calculation.
    public void calculateLatency(long timeIndex, long windowReq){
        //Calculate total latency and # of tuples in current time slots
        long complete = completed.getOrDefault(timeIndex, 0l);
        long lastComplete = completed.getOrDefault(timeIndex - 1, 0l);
        long arrive = arrived.getOrDefault(arrivedIndex, 0l);
        long lastArrive = arrived.getOrDefault(arrivedIndex - 1, 0l);
        long totalDelay = 0;
        firstIndex.put(timeIndex, arrivedIndex);
        while(arrivedIndex <= timeIndex && lastArrive < complete){
            if(arrive > lastComplete){ //Should count into this slot
                long number = Math.min(complete, arrive) - Math.max(lastComplete, lastArrive);
                totalDelay += number * (timeIndex - arrivedIndex);
            }
            arrivedIndex++;
            arrive = arrived.getOrDefault(arrivedIndex, 0l);
            lastArrive = arrived.getOrDefault(arrivedIndex - 1, 0l);
        }
        //TODO: find out when to --
        arrivedIndex--;
        endIndex.put(timeIndex, arrivedIndex);
        totalLatency.put(timeIndex, totalDelay);
        if(totalLatency.containsKey(timeIndex - windowReq)){
            totalLatency.remove(timeIndex - windowReq);
            firstIndex.remove(timeIndex - windowReq);
            endIndex.remove(timeIndex-windowReq);
        }
    }
}


class ExecutorState{
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.ExecutorState.class);
    public Map<String, Integer> configMem, configCpu;
    public Map<String, Double> usedMem;
    public Map<Long, Map<String, Double>> cpuUsages;
    public Map<Long, Map<String, Long>> pgMajFault;
    public Map<String, Double> currentCpuUsages, currentPGFaults;
    public int totalMem = 0;
    public int totalCPU = 0;
    private final double decayFactor;
    long timeIndex, lastPGIndex, lastUsageIndex;
    private final long windowReq;
    public ExecutorState(long windowReq, double decayFactor){
        configMem = new HashMap<>();
        configCpu = new HashMap<>();
        usedMem = new HashMap<>();
        cpuUsages = new HashMap<>();
        pgMajFault = new HashMap<>();
        timeIndex = 0;
        lastPGIndex = 0;
        lastUsageIndex = 0;
        currentCpuUsages = new HashMap<>();
        currentPGFaults = new HashMap<>();
        this.windowReq = windowReq;
        this.decayFactor = decayFactor;
    }
    public void updateResource(long timeIndex, Map<String, Resource> executorResource, Map<String, Double> executorMemUsed,
                               Map<String, Double> executorCpuUsage, Map<String, Long> pgMajFault){
        this.usedMem.clear();
        this.configCpu.clear();
        this.configMem.clear();
        this.timeIndex = timeIndex;
        executorResource.remove("000001");

        for(Map.Entry<String, Resource> entry : executorResource.entrySet()){
            String executor = entry.getKey();
            Resource resource = entry.getValue();
            configCpu.put(executor, resource.getVirtualCores());
            configMem.put(executor, resource.getMemory());
        }

        for(Map.Entry<String, Double> entry : executorMemUsed.entrySet()){
            this.usedMem.put(entry.getKey(), entry.getValue());
        }

        Map<String, Double> cpuUsage = new HashMap<>();
        for(Map.Entry<String, Double> entry : executorCpuUsage.entrySet()){
            String key = entry.getKey();
            double value = entry.getValue();
            cpuUsage.put(key, value);
            if (!currentCpuUsages.containsKey(key)){
                currentCpuUsages.put(key, value);
            } else {
                double old = currentCpuUsages.get(key);
                double newValue = old * decayFactor + value * (1 - decayFactor);
                currentCpuUsages.put(key, newValue);
            }
        }
        this.cpuUsages.put(timeIndex, cpuUsage);

        Map<String, Long> pgFault = new HashMap<>();
        for(Map.Entry<String, Long> entry : pgMajFault.entrySet()){
            String key = entry.getKey();
            double value = entry.getValue();
            pgFault.put(key, entry.getValue());
            if (!currentPGFaults.containsKey(key)){
                currentPGFaults.put(key, value);
            } else {
                double old = currentPGFaults.get(key);
                double newValue = old * decayFactor + value * (1 - decayFactor);
                currentPGFaults.put(key, newValue);
            }
        }
        this.pgMajFault.put(timeIndex, pgFault);
        calibrate();

//        dropCpuUsage(timeIndex);
    }

    //Only called when time n is valid, also update substreamLastValid
    private void calibratePGFault(long timeIndex){
        if (!pgMajFault.containsKey(timeIndex))
            return;
        if(lastPGIndex == 0){
            Map<String, Long> pgFaults1 = pgMajFault.get(timeIndex);
            Map<String, Long> pgFaults0 = new HashMap<>();
            for(Map.Entry<String, Long> entry : pgFaults1.entrySet()){
                pgFaults0.put(entry.getKey(), 0l);
            }
            pgMajFault.put(0l, pgFaults0);
        }
        if(lastPGIndex < timeIndex - 1) {
            Map<String, Long> pgFaults0 = pgMajFault.get(lastPGIndex);
            Map<String, Long> pgFaults1 = pgMajFault.get(timeIndex);
            for(long i = lastPGIndex + 1; i < timeIndex; i ++) {
                Map<String, Long> pgs = new HashMap<>();
                for(Map.Entry<String, Long> entry : pgFaults0.entrySet()){
                    long c0 = entry.getValue();
                    long c1 = pgFaults1.get(entry.getKey());
                    long ci = (c1 - c0) * (i - lastPGIndex) / (timeIndex - lastPGIndex) + c0;
                    pgs.put(entry.getKey(), ci);
                }
                this.pgMajFault.put(i, pgs);
            }
        }
        lastPGIndex = timeIndex;
    }

    private void calibrateUsage(long timeIndex){
        if (!cpuUsages.containsKey(timeIndex))
            return;
        if(lastUsageIndex == 0){
            Map<String, Double> cpuUsage1 = cpuUsages.get(timeIndex);
            Map<String, Double> cpuUsage0 = new HashMap<>();
            for(Map.Entry<String, Double> entry : cpuUsage1.entrySet()){
                cpuUsage0.put(entry.getKey(), 0.0);
            }
            cpuUsages.put(0l, cpuUsage0);
        }
        if(lastUsageIndex < timeIndex - 1) {
            Map<String, Double> cpuUsage0 = cpuUsages.get(lastUsageIndex);
            Map<String, Double> cpuUsage1 = cpuUsages.get(timeIndex);
            for(long i = lastUsageIndex + 1; i < timeIndex; i ++) {
                Map<String, Double> usages = new HashMap<>();
                for(Map.Entry<String, Double> entry : cpuUsage0.entrySet()){
                    Double c0 = entry.getValue();
                    Double c1 = cpuUsage1.get(entry.getKey());
                    Double ci = (c1 - c0) * (i - lastUsageIndex) / (timeIndex - lastUsageIndex) + c0;
                    usages.put(entry.getKey(), ci);
                }
                this.cpuUsages.put(i, usages);
            }
        }
        lastUsageIndex = timeIndex;
    }

    private void calibrate(){
        calibratePGFault(timeIndex);
        calibrateUsage(timeIndex);
    }

    private void dropCpuUsage(long timeIndex){
        //Drop cpu usage
        List<Long> removeIndex = new LinkedList<>();
        for(long index:cpuUsages.keySet()){
            if(index < timeIndex - 2*windowReq){
                removeIndex.add(index);
            }
        }
        for(long index:removeIndex){
            cpuUsages.remove(index);
        }
        removeIndex.clear();
    }

    private void updateAvgCpuUsage(long timeIndex){
        long n0 = timeIndex - windowReq + 1;
        Map<String, Double> totalUsage = new HashMap<>();
        long num = 0;
        if(n0<1){
            n0 = 1;
            LOG.warn("Calculate instant delay index smaller than window size!");
        }
        for(long i = n0; i <= timeIndex; i++){
            if(cpuUsages.containsKey(i)){
                Map<String, Double> usages = cpuUsages.get(i);
                for(Map.Entry<String, Double> entry: usages.entrySet()){
                    String executor = entry.getKey();
                    Double usage = entry.getValue();
                    if (!totalUsage.containsKey(executor))
                        totalUsage.put(executor, usage);
                    else{
                        Double oldValue = totalUsage.get(executor);
                        totalUsage.put(executor, oldValue + usage);
                    }
                }
                num += 1;
            }
        }
        for(Map.Entry<String, Double> entry: totalUsage.entrySet()){
            String executor = entry.getKey();
            Double usage = entry.getValue();
        }
    }
}