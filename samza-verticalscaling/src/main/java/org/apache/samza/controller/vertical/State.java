package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.config.MapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class State {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.State.class);
    private Map<Long, Map<Integer, List<Integer>>> mappings;
    public Map<Integer, SubstreamState> substreamStates;
    public ExecutorState executorState;
    private long lastValidTimeIndex;
    private long currentTimeIndex;
    final long metricsRetreiveInterval, windowReq;
    public Map<String, List<String>> executorMapping;
    public State(long metricsRetreiveInterval, long windowReq) {
        this.metricsRetreiveInterval = metricsRetreiveInterval;
        this.windowReq = windowReq;
        currentTimeIndex = 0;
        lastValidTimeIndex = 0;
        mappings = new HashMap<>();
        substreamStates = new HashMap<>();
        executorState = new ExecutorState(windowReq);
    }

    public int substreamIdFromStringToInt(String partition){
        return Integer.parseInt(partition.substring(partition.indexOf(' ') + 1));
    }
    public int executorIdFromStringToInt(String executor){
        return Integer.parseInt(executor);
    }

    public void init(Map<String, List<String>> executorMapping){
        LOG.info("Initialize time point 0...");
        System.out.println("New mapping at time: " + 0 + " mapping:" + executorMapping);
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
                while (remainedIndex < substreamState.arrivedIndex - 1 && remainedIndex < timeIndex - windowReq) {
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
            if(index < timeIndex - windowReq - 1){
                removeIndex.add(index);
            }
        }
        for(long index:removeIndex){
            mappings.remove(index);
        }
        removeIndex.clear();
        if (checkValidity(substreamValid)) {
            for (long index = lastValidTimeIndex + 1; index <= timeIndex; index++) {
                for (String executor : executorMapping.keySet()) {
                    for (String substream : executorMapping.get(executor)) {
                        //Drop completed
                        if (substreamStates.get(substreamIdFromStringToInt(substream)).completed.containsKey(index - windowReq - 1)) {
                            substreamStates.get(substreamIdFromStringToInt(substream)).completed.remove(index - windowReq - 1);
                        }
                    }
                }

            }
            lastValidTimeIndex = timeIndex;
        }

        LOG.info("Useless state dropped, current arrived size: " + totalSize + " mapping size: " + mappings.size());
    }

    public boolean checkValidity(Map<String, Boolean> substreamValid){
        if(substreamValid == null)return false;

        //Current we don't haven enough time slot to calculate model
        if(currentTimeIndex < windowReq){
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
    private void calibrateSubstream(int substream, long timeIndex){
        SubstreamState substreamState = substreamStates.get(substream);
        long n0 = substreamState.lastValidIndex;
        substreamState.lastValidIndex = timeIndex;
        if(n0 < timeIndex - 1) {
            //LOG.info("Calibrate state for " + substream + " from time=" + n0);
            long a0 = getSubstreamArrived(substream, n0);
            long c0 = getSubstreamCompleted(substream, n0);
            long a1 = getSubstreamArrived(substream, timeIndex);
            long c1 = getSubstreamCompleted(substream, timeIndex);
            for (long i = n0 + 1; i < timeIndex; i++) {
                long ai = (a1 - a0) * (i - n0) / (timeIndex - n0) + a0;
                long ci = (c1 - c0) * (i - n0) / (timeIndex - n0) + c0;
                substreamState.arrived.put(i, ai);
                substreamState.completed.put(i, ci);
            }
        }

        System.out.println("Model, time " + timeIndex  + " , substream " + substream + " arrived: " + substreamState.arrived.get(timeIndex));
        System.out.println("Model, time " + timeIndex  + " , substream " + substream + " completed: " + substreamState.completed.get(timeIndex));
        //Calculate total latency here
        for(long index = n0 + 1; index <= timeIndex; index++){
            substreamState.calculateLatency(index, windowReq);
//            calculateSubstreamLatency(substream, index);
        }
    }

    //Calibrate whole state including mappings and utilizations
    public void calibrate(Map<String, Boolean> substreamValid){
        //Calibrate mappings
        if(mappings.containsKey(currentTimeIndex)){
            for(long t = currentTimeIndex - 1; t >= 0 && t >= currentTimeIndex - windowReq - 1; t--){
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
                calibrateSubstream(substreamIdFromStringToInt(substream), currentTimeIndex);
            }
            else {
                System.out.println("invalid substream: " + substreamValid);
            }
        }
    }



    public void insert(long timeIndex, Map<String, Long> substreamArrived,
                        Map<String, Long> substreamProcessed,
                        Map<String, List<String>> executorMapping) { //Normal update
        LOG.info("Debugging, metrics retrieved data, time: " + timeIndex + " substreamArrived: "+ substreamArrived + " substreamProcessed: "+ substreamProcessed + " assignment: " + executorMapping);

        currentTimeIndex = timeIndex;

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

        for(String substream:substreamArrived.keySet()){
            substreamStates.get(substreamIdFromStringToInt(substream)).arrived.put(currentTimeIndex, substreamArrived.get(substream));
        }
        for(String substream: substreamProcessed.keySet()){
            substreamStates.get(substreamIdFromStringToInt(substream)).completed.put(currentTimeIndex, substreamProcessed.get(substream));
        }
    }

    public long getCurrentTimeIndex() {
        return currentTimeIndex;
    }

    public boolean checkArrivedPositive(int id, long timeIndex){
        return (substreamStates.get(id).arrived.containsKey(timeIndex) && substreamStates.get(id).arrived.get(timeIndex) != null && substreamStates.get(id).arrived.get(timeIndex) > 0);
    }

    public Map<String, Double> getCPUUsage(){
        return executorState.cpuUsage;
    }

    public Map<String, Integer> getCPUConfig(){
        return executorState.configCpu;
    }

}


class SubstreamState{
    public Map<Long, Long> arrived, completed; //Instead of actual time, use the n-th time point as key
    public long lastValidIndex;    //Last valid state time point
    //For calculate instant delay
    public Map<Long, Long> totalLatency;
    public Map<Long, Long> firstIndex, endIndex;
    public long arrivedIndex, remainedIndex;
    public SubstreamState(){
        arrived = new HashMap<>();
        completed = new HashMap<>();
        totalLatency = new HashMap<>();
        firstIndex = new HashMap<>();
        endIndex = new HashMap<>();
    }

    public void init(){
        arrived.put(0l, 0l);
        completed.put(0l, 0l);
        lastValidIndex = 0l;
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
        }
    }
}


class ExecutorState{
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.ExecutorState.class);
    public Map<String, Integer> configMem, configCpu;
    public Map<String, Double> usedMem, cpuUsage;
    public Map<String, Long> pgMajFault;
    public Map<Long, Map<String, Double>> allCpuUsage;
    long timeIndex;
    private final long windowReq;
    public ExecutorState(long windowReq){
        configMem = new HashMap<>();
        configCpu = new HashMap<>();
        usedMem = new HashMap<>();
        cpuUsage = new HashMap<>();
        pgMajFault = new HashMap<>();
        allCpuUsage = new HashMap<>();
        timeIndex = 0;
        this.windowReq = windowReq;
    }
    public void updateResource(long timeIndex, Map<String, Resource> executorResource, Map<String, Double> executorMemUsed,
                               Map<String, Double> executorCpuUsage, Map<String, Long> pgMajFault){

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
        for(Map.Entry<String, Double> entry : executorCpuUsage.entrySet()){
            this.cpuUsage.put(entry.getKey(), entry.getValue());
        }
        for(Map.Entry<String, Long> entry : pgMajFault.entrySet()){
            this.pgMajFault.put(entry.getKey(), entry.getValue());
        }
//        dropCpuUsage(timeIndex);
    }

    private void dropCpuUsage(long timeIndex){
        //Drop cpu usage
        List<Long> removeIndex = new LinkedList<>();
        for(long index:allCpuUsage.keySet()){
            if(index < timeIndex - 2*windowReq){
                removeIndex.add(index);
            }
        }
        for(long index:removeIndex){
            allCpuUsage.remove(index);
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
            if(allCpuUsage.containsKey(i)){
                Map<String, Double> usages = allCpuUsage.get(i);
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