package org.apache.samza.controller;

import com.sun.org.apache.regexp.internal.RE;
import javafx.util.Pair;
import org.apache.commons.collections.map.HashedMap;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.config.Config;
import org.apache.samza.controller.vertical.ResourceChecker;
import org.apache.samza.storage.kv.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.DoubleBinaryOperator;

//Under development

public class LatencyGuarantor extends StreamSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.LatencyGuarantor.class);
    private long latencyReq, windowReq; //Window requirment is stored as number of timeslot
    private double l_low, l_high; // Check instantDelay  < l and longtermDelay < req
    private double initialServiceRate, decayFactor, conservativeFactor; // Initial prediction by user or system on service rate.
    long migrationInterval;
    private boolean isStarted;
    private Examiner examiner;
    private Map<String, Resource> shrinks = new HashMap<String, Resource>();
    private Map<String, Resource> expands = new HashMap<String, Resource>();
    private long deltaTime;
    private long startTime = 0;
    private boolean isIncrease = false;
    private final ResourceChecker resourceChecker;
    private final Thread resourceCheckerThread;

    public LatencyGuarantor(Config config){
        super(config);
        latencyReq = config.getLong("streamswitch.requirement.latency", 1000); //Unit: millisecond
        windowReq = config.getLong("streamswitch.requirement.window", 2000) / metricsRetreiveInterval; //Unit: # of time slots
        l_low = config.getDouble("streamswitch.system.l_low", 50); //Unit: millisecond
        l_high = config.getDouble("streamswtich.system.l_high", 100);
        initialServiceRate = config.getDouble("streamswitch.system.initialservicerate", 0.2);
        decayFactor = config.getDouble("streamswitch.system.decayfactor", 0.875);
        conservativeFactor = config.getDouble("streamswitch.system.conservative", 1.0);
        migrationInterval = config.getLong("streamswitch.system.migration_interval", 5000l);
        deltaTime = config.getLong("streamswitch.system.delta_time", 1000);
        String jobName = config.get("job.name");
        examiner = new Examiner();
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

    class Examiner{
        class State {
            private Map<Long, Map<Integer, List<Integer>>> mappings;
            class SubstreamState{
                private Map<Long, Long> arrived, completed; //Instead of actual time, use the n-th time point as key
                private long lastValidIndex;    //Last valid state time point
                //For calculate instant delay
                private Map<Long, Long> totalLatency;
                private long arrivedIndex, remainedIndex;
                SubstreamState(){
                    arrived = new HashMap<>();
                    completed = new HashMap<>();
                    totalLatency = new HashMap<>();
                }
            }
            Map<Integer, SubstreamState> substreamStates;
            private long lastValidTimeIndex;
            private long currentTimeIndex;
            private State() {
                currentTimeIndex = 0;
                lastValidTimeIndex = 0;
                mappings = new HashMap<>();
                substreamStates = new HashMap<>();
            }

            private int substreamIdFromStringToInt(String partition){
                return Integer.parseInt(partition.substring(partition.indexOf(' ') + 1));
            }
            private int executorIdFromStringToInt(String executor){
                return Integer.parseInt(executor);
            }

            private void init(Map<String, List<String>> executorMapping){
                LOG.info("Initialize time point 0...");
                System.out.println("New mapping at time: " + 0 + " mapping:" + executorMapping);
                for (String executor : executorMapping.keySet()) {
                    for (String substream : executorMapping.get(executor)) {
                        SubstreamState substreamState = new SubstreamState();
                        substreamState.arrived.put(0l, 0l);
                        substreamState.completed.put(0l, 0l);
                        substreamState.lastValidIndex = 0l;
                        substreamState.arrivedIndex = 1l;
                        substreamState.remainedIndex = 0l;
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

            //Calculate the total latency in new slot. Drop useless data during calculation.
            private void calculateSubstreamLatency(Integer substream, long timeIndex){
                //Calculate total latency and # of tuples in current time slots
                SubstreamState substreamState = substreamStates.get(substream);
                long arrivalIndex = substreamState.arrivedIndex;
                long complete = getSubstreamCompleted(substream, timeIndex);
                long lastComplete = getSubstreamCompleted(substream, timeIndex - 1);
                long arrived = getSubstreamArrived(substream, arrivalIndex);
                long lastArrived = getSubstreamArrived(substream, arrivalIndex - 1);
                long totalDelay = 0;
                while(arrivalIndex <= timeIndex && lastArrived < complete){
                    if(arrived > lastComplete){ //Should count into this slot
                        long number = Math.min(complete, arrived) - Math.max(lastComplete, lastArrived);
                        totalDelay += number * (timeIndex - arrivalIndex);
                    }
                    arrivalIndex++;
                    arrived = getSubstreamArrived(substream, arrivalIndex);
                    lastArrived = getSubstreamArrived(substream, arrivalIndex - 1);
                }
                //TODO: find out when to --
                arrivalIndex--;
                substreamState.totalLatency.put(timeIndex, totalDelay);
                if(substreamState.totalLatency.containsKey(timeIndex - windowReq)){
                    substreamState.totalLatency.remove(timeIndex - windowReq);
                }
                if(arrivalIndex > substreamState.arrivedIndex) {
                    substreamState.arrivedIndex = arrivalIndex;
                }
            }

            private void drop(long timeIndex, Map<String, Boolean> substreamValid) {
                long totalSize = 0;

                //Drop arrived
                for(String substream: substreamValid.keySet()) {
                    SubstreamState substreamState = substreamStates.get(substreamIdFromStringToInt(substream));
                    if (substreamValid.get(substream)) {
                        long remainedIndex = substreamState.remainedIndex;
                        while (remainedIndex < substreamState.arrivedIndex - 1 && remainedIndex < timeIndex - windowReq) {
                            substreamState.arrived.remove(remainedIndex);
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

            //Only called when time n is valid, also update substreamLastValid
            private void calibrateSubstream(int substream, long timeIndex){
                SubstreamState substreamState = substreamStates.get(substream);
                long n0 = substreamState.lastValidIndex;
                substreamState.lastValidIndex = timeIndex;
                if(n0 < timeIndex - 1) {
                    //LOG.info("Calibrate state for " + substream + " from time=" + n0);
                    long a0 = state.getSubstreamArrived(substream, n0);
                    long c0 = state.getSubstreamCompleted(substream, n0);
                    long a1 = state.getSubstreamArrived(substream, timeIndex);
                    long c1 = state.getSubstreamCompleted(substream, timeIndex);
                    for (long i = n0 + 1; i < timeIndex; i++) {
                        long ai = (a1 - a0) * (i - n0) / (timeIndex - n0) + a0;
                        long ci = (c1 - c0) * (i - n0) / (timeIndex - n0) + c0;
                        substreamState.arrived.put(i, ai);
                        substreamState.completed.put(i, ci);
                    }
                }

                //Calculate total latency here
                for(long index = n0 + 1; index <= timeIndex; index++){
                    calculateSubstreamLatency(substream, index);
                }
            }

            //Calibrate whole state including mappings and utilizations
            private void calibrate(Map<String, Boolean> substreamValid){
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
                }
            }



            private void insert(long timeIndex, Map<String, Long> substreamArrived,
                                Map<String, Long> substreamProcessed,
                                Map<String, List<String>> executorMapping) { //Normal update
                LOG.info("Debugging, metrics retrieved data, time: " + timeIndex + " substreamArrived: "+ substreamArrived + " substreamProcessed: "+ substreamProcessed + " assignment: " + executorMapping);

                currentTimeIndex = timeIndex;

                HashMap<Integer, List<Integer>> mapping = new HashMap<>();
                for(String executor: executorMapping.keySet()) {
                    List<Integer> partitions = new LinkedList<>();
                    for (String partitionId : executorMapping.get(executor)) {
                        partitions.add(substreamIdFromStringToInt(partitionId));
                    }
                    mapping.put(executorIdFromStringToInt(executor), partitions);
                }
                mappings.put(currentTimeIndex, mapping);

                LOG.info("Current time " + timeIndex);

                for(String substream:substreamArrived.keySet()){
                    substreamStates.get(substreamIdFromStringToInt(substream)).arrived.put(currentTimeIndex, substreamArrived.get(substream));
                }
                for(String substream: substreamProcessed.keySet()){
                    substreamStates.get(substreamIdFromStringToInt(substream)).completed.put(currentTimeIndex, substreamProcessed.get(substream));
                }

            }
        }
        //Model here is only a snapshot
        class Model {
            private State state;
            Map<String, Double> substreamArrivalRate, executorArrivalRate, executorServiceRate, executorInstantaneousDelay; //Longterm delay could be calculated from arrival rate and service rate
            Map<String, Long> executorCompleted, executorArrived; //For debugging instant delay
            private Model(State state){
                substreamArrivalRate = new HashMap<>();
                executorArrivalRate = new HashMap<>();
                executorServiceRate = new HashMap<>();
                executorInstantaneousDelay = new HashMap<>();
                executorCompleted = new HashMap<>();
                executorArrived = new HashMap<>();
                this.state = state;
            }

            private double calculateLongTermDelay(double arrival, double service){
                // Conservative !
                service = conservativeFactor * service;
                if(arrival < 1e-15)return 0.0;
                if(service < arrival + 1e-15)return 1e100;
                return 1.0/(service - arrival);
            }

            // 1 / ( u - n ). Return  1e100 if u <= n
            private double getLongTermDelay(String executorId){
                double arrival = executorArrivalRate.get(executorId);
                double service = executorServiceRate.get(executorId);
                return calculateLongTermDelay(arrival, service);
            }

            private double calculateSubstreamArrivalRate(String substream, long n0, long n1){
                if(n0 < 0)n0 = 0;
                long time = state.getTimepoint(n1) - state.getTimepoint(n0);
                long totalArrived = state.getSubstreamArrived(state.substreamIdFromStringToInt(substream), n1) - state.getSubstreamArrived(state.substreamIdFromStringToInt(substream), n0);
                if(time > 1e-9)return totalArrived / ((double)time);
                return 0.0;
            }

            //Window average delay
            private double calculateExecutorInstantaneousDelay(String executorId, long timeIndex){
                long totalDelay = 0;
                long totalCompleted = 0;
                long totalArrived = 0;
                long n0 = timeIndex - windowReq + 1;
                if(n0<1){
                    n0 = 1;
                    LOG.warn("Calculate instant delay index smaller than window size!");
                }
                for(long i = n0; i <= timeIndex; i++){
                    if(state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))){
                        for(int substream: state.getMapping(i).get(state.executorIdFromStringToInt(executorId))){
                            totalCompleted += state.getSubstreamCompleted(substream, i) - state.getSubstreamCompleted(substream, i - 1);
                            totalArrived += state.getSubstreamArrived(substream, i) - state.getSubstreamArrived(substream, i - 1);
                            totalDelay += state.getSubstreamLatency(substream, i);
                        }
                    }
                }
                //In state, latency is count as # of timeslots, need to transfer to real time
                executorCompleted.put(executorId, totalCompleted);
                executorArrived.put(executorId, totalArrived);
                if(totalCompleted > 0) return totalDelay * metricsRetreiveInterval / ((double)totalCompleted);
                return 0;
            }

            //Calculate model snapshot from state
            private void update(long timeIndex, Map<String, Double> serviceRate, Map<String, List<String>> executorMapping){
                LOG.info("Updating model snapshot, clear old data...");
                //substreamArrivalRate.clear();
                executorArrivalRate.clear();
                //executorInstantaneousDelay.clear();
                executorCompleted.clear();
                executorArrived.clear();
                Map<String, Double> utils = new HashMap<>();
                for(String executor: executorMapping.keySet()){
                    double arrivalRate = 0;
                    for(String substream: executorMapping.get(executor)){
                        double oldArrivalRate = substreamArrivalRate.getOrDefault(substream, 0.0);
                        double t = oldArrivalRate * decayFactor + calculateSubstreamArrivalRate(substream, timeIndex - windowReq, timeIndex) * (1.0 - decayFactor);
                        substreamArrivalRate.put(substream, t);
                        arrivalRate += t;
                    }
                    executorArrivalRate.put(executor, arrivalRate);
                    //double util = state.getExecutorUtilization(state.executorIdFromStringToInt(executor));
                    //utils.put(executor, util);
                    //double mu = calculateExecutorServiceRate(executor, util, timeIndex);
                    /*if(util > 0.5 && util <= 1){ //Only update true service rate (capacity when utilization > 50%, so the error will be smaller)
                        mu /= util;
                        executorServiceRate.put(executor, mu);
                    }else if(!executorServiceRate.containsKey(executor) || (util < 0.3 && executorServiceRate.get(executor) < arrivalRate * 1.5))executorServiceRate.put(executor, arrivalRate * 1.5); //Only calculate the service rate when no historical service rate*/

                    if(!executorServiceRate.containsKey(executor)){
                        executorServiceRate.put(executor, initialServiceRate);
                    }
                    if(serviceRate.containsKey(executor)) {
                        double oldServiceRate = executorServiceRate.get(executor);
                        double newServiceRate = oldServiceRate * decayFactor + serviceRate.get(executor) * (1 - decayFactor);
                        executorServiceRate.put(executor, newServiceRate);
                    }

                    double oldInstantaneousDelay = executorInstantaneousDelay.getOrDefault(executor, 0.0);
                    double newInstantaneousDelay = oldInstantaneousDelay * decayFactor + calculateExecutorInstantaneousDelay(executor, timeIndex) * (1.0 - decayFactor);
                    executorInstantaneousDelay.put(executor, newInstantaneousDelay);
                }
                //Debugging
                LOG.info("Debugging, avg utilization: " + utils);
                LOG.info("Debugging, partition arrival rate: " + substreamArrivalRate);
                LOG.info("Debugging, executor avg service rate: " + executorServiceRate);
                LOG.info("Debugging, executor avg delay: " + executorInstantaneousDelay);
            }
        }


        class ResourceInfo{
            public Map<String, Integer> executorConfigMem, executorConfigCpu, executorResourceState;
            public Map<String, Double> executorLastUsedMem, executorUsedMem, executorCpuUsage, executorDeltaMem;
            long timeIndex;
            private ResourceInfo(){
                executorConfigMem = new HashMap<>();
                executorConfigCpu = new HashMap<>();
                executorResourceState = new HashMap<>();
                executorUsedMem = new HashMap<>();
                executorCpuUsage = new HashMap<>();
                executorDeltaMem = new HashMap<>();
                executorLastUsedMem = new HashMap<>();
                timeIndex = 0;
            }

            private void getDelta(){

            }

            private void updateResource(long timeIndex, Map<String, Resource> executorResource, Map<String, Double> executorMemUsed, Map<String, Double> executorCpuUsage){
                this.timeIndex = timeIndex;
                executorLastUsedMem.putAll(this.executorUsedMem);
                for(Map.Entry<String, Resource> entry : executorResource.entrySet()){
                    String executor = entry.getKey();
                    Resource resource = entry.getValue();
                    executorConfigCpu.put(executor, resource.getVirtualCores());
                    executorConfigMem.put(executor, resource.getMemory());
                    if(!executorMemUsed.containsKey(executor))
                        continue;
                    if (resource.getMemory() - executorMemUsed.get(executor) < 30){
                        //1 for busy
                        executorResourceState.put(executor, 1);
                    } else {
                        // 0 for idle
                        executorResourceState.put(executor, 0);
                    }
                    if (executorLastUsedMem.containsKey(executor)){
                        System.out.println("Last Used Mem: " + this.executorLastUsedMem);
                        System.out.println("This time Used Mem: " + executorMemUsed);
                        Double oldDelta = 0.0;
                        if (! executorDeltaMem.containsKey(executor))
                            oldDelta = executorMemUsed.get(executor) - executorLastUsedMem.get(executor);
                        else
                            oldDelta = executorDeltaMem.get(executor);
                        Double newDelta = decayFactor * oldDelta + (1 - decayFactor) * (executorMemUsed.get(executor) - executorLastUsedMem.get(executor));
                        executorDeltaMem.put(executor, newDelta);
                    }
                }
                this.executorUsedMem.putAll(executorMemUsed);
                System.out.println(this.executorResourceState);
                this.executorCpuUsage.putAll(executorCpuUsage);
            }
        }

        private Model model;
        private State state;
        private ResourceInfo resourceInfo;
        private Map<String, Resource> resources;
        private Map<String, Double> memUsed;
        private Map<String, Double> cpuUsage;
        Examiner(){
            state = new State();
            model = new Model(state);
            resourceInfo = new ResourceInfo();
        }

        private void init(Map<String, List<String>> executorMapping){
            state.init(executorMapping);
        }

        private boolean checkValidity(Map<String, Boolean> substreamValid){
            if(substreamValid == null)return false;

            //Current we don't haven enough time slot to calculate model
            if(state.currentTimeIndex < windowReq){
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

        private boolean updateState(long timeIndex, Map<String, Long> substreamArrived, Map<String, Long> substreamProcessed, Map<String, Boolean> substreamValid, Map<String, List<String>> executorMapping){
            LOG.info("Updating state...");
            state.insert(timeIndex, substreamArrived, substreamProcessed, executorMapping);
            state.calibrate(substreamValid);
            state.drop(timeIndex, substreamValid);
            //Debug & Statistics
            HashMap<Integer, Long> arrived = new HashMap<>(), completed = new HashMap<>();
            for(int substream: state.substreamStates.keySet()) {
                arrived.put(substream, state.substreamStates.get(substream).arrived.get(state.currentTimeIndex));
                completed.put(substream, state.substreamStates.get(substream).completed.get(state.currentTimeIndex));
            }
            System.out.println("State, time " + timeIndex  + " , Partition Arrived: " + arrived);
            System.out.println("State, time " + timeIndex  + " , Partition Completed: " + completed);
            if(checkValidity(substreamValid)){
                //state.calculate(timeIndex);
                //state.drop(timeIndex);
                return true;
            }else return false;
        }

        private void updateModel(long timeIndex, Map<String, Double> serviceRate, Map<String, List<String>> executorMapping){
            LOG.info("Updating Model");
            model.update(timeIndex, serviceRate, executorMapping);
            //Debug & Statistics
            if(true){
                HashMap<String, Double> longtermDelay = new HashMap<>();
                for(String executorId: executorMapping.keySet()){
                    double delay = model.getLongTermDelay(executorId);
                    longtermDelay.put(executorId, delay);
                }
                System.out.println("Model, time " + timeIndex  + " , Arrival Rate: " + model.executorArrivalRate);
                System.out.println("Model, time " + timeIndex  + " , Service Rate: " + model.executorServiceRate);
                System.out.println("Model, time " + timeIndex  + " , Instantaneous Delay: " + model.executorInstantaneousDelay);
                System.out.println("Model, time " + timeIndex  + " , executors completed: " + model.executorCompleted);
                System.out.println("Model, time " + timeIndex  + " , executors arrived: " + model.executorArrived);
                System.out.println("Model, time " + timeIndex  + " , Longterm Delay: " + longtermDelay);
                System.out.println("Model, time " + timeIndex  + " , Partition Arrival Rate: " + model.substreamArrivalRate);
            }
        }
        private Map<String, Double> getInstantDelay(){
            return model.executorInstantaneousDelay;
        }

        private Map<String, Double> getLongtermDelay(){
            HashMap<String, Double> delay = new HashMap<>();
            for(String executor: executorMapping.keySet()){
                delay.put(executor, model.getLongTermDelay(executor));
            }
            return delay;
        }

        //DrG
        private void updateResourceState(long timeIndex, Map<String, Resource> executorResource, Map<String, Double> executorMemUsed, Map<String, Double> executorCpuUsage){
            this.resourceInfo.updateResource(timeIndex, executorResource, executorMemUsed, executorCpuUsage);
            this.resources = executorResource;
            this.memUsed = executorMemUsed;
            this.cpuUsage = executorCpuUsage;
            System.out.println("Model, time " + timeIndex  + ", executor configure resource: " + this.resources);
            System.out.println("Model, time " + timeIndex  + ", executor used memory: " + this.memUsed);
            System.out.println("Model, time " + timeIndex  + ", executor cpu usages: " + this.cpuUsage);
        }
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
        Map<String, Double> executorNonHeapUsed =
                (HashMap<String, Double>) (metrics.get("NonHeapUsed"));
        System.out.println("Model, time " + timeIndex  + ", executor heap used: " + executorHeapUsed);
        System.out.println("Model, time " + timeIndex  + ", executor non heap used: " + executorNonHeapUsed);


//        Long queue = 0L;
//        for (Map.Entry<String, Long> entry: substreamArrived.entrySet()){
//            String sub = entry.getKey();
//            Long processed = 0L;
//            if (substreamProcessed.containsKey(sub))
//                processed = substreamProcessed.get(sub);
//            Long subQueue = entry.getValue() - processed;
//            queue += subQueue;
//        }
        examiner.updateResourceState(timeIndex, executorResources, executorMemUsed, executorCpuUsage);
        //Memory usage
        //LOG.info("Metrics size arrived size=" + substreamArrived.size() + " processed size=" + substreamProcessed.size() + " valid size=" + substreamValid.size() + " utilization size=" + executorUtilization.size());
        if(examiner.updateState(timeIndex, substreamArrived, substreamProcessed, substreamValid, executorMapping)){
            examiner.updateModel(timeIndex, executorServiceRate, executorMapping);
            return true;
        }
        return false;
    }

    private boolean diagnose(Examiner examiner){
        String giver = "000002";
        String taker = "000003";
        int giverMem = 0, giverCpu = 0, takerMem = 0, takerCpu = 0;

        LOG.info("Instant Delay: " + examiner.getInstantDelay());

        if(examiner.getInstantDelay().get(giver) == examiner.getInstantDelay().get(taker)){
            return false;
        } else if(examiner.getInstantDelay().get(giver) > examiner.getInstantDelay().get(taker)){
            giver = "000003";
            taker = "000002";
        }
        Resource giverConfig = examiner.resources.get(giver);
        Resource takerConfig = examiner.resources.get(taker);

        if (examiner.cpuUsage.get(taker)/takerConfig.getVirtualCores() > 0.90)
            takerCpu = 1;
        if (examiner.memUsed.get(taker) >= takerConfig.getMemory())
            takerMem = 100;
        if (takerCpu == 0 && takerMem == 0)
            return false;

        if (giverConfig.getVirtualCores() <= 1)
            giverCpu = 0;
        else if (examiner.cpuUsage.get(giver)/giverConfig.getVirtualCores() < 0.90)
            giverCpu = 1;

        if (giverConfig.getMemory() <= 300)
            giverMem = 0;
        else if(giverConfig.getMemory() > examiner.memUsed.get(giver))
            giverMem = giverConfig.getMemory() - examiner.memUsed.get(giver).intValue();

        if (giverCpu == 0 && giverMem == 0){
            if (giverConfig.getVirtualCores() > 1)
                giverCpu = 1;
            if (giverConfig.getMemory() > 300)
                giverMem = giverConfig.getMemory() - 300;
        }
        int finalCpu = min(giverCpu, takerCpu);
        int finalMem = min(giverMem, takerMem);
        if (finalCpu == 0 && finalMem == 0)
            return false;

        String msg = "before transition, taker " + taker + " has CPU: " + takerConfig.getVirtualCores() + ", has Memory: " + takerConfig.getMemory() + "M.";
        LOG.info(msg);
        msg = "before transition, giver " + giver + " has CPU: " + giverConfig.getVirtualCores() + ", has Memory: " + giverConfig.getMemory() + "M.";
        LOG.info(msg);
        msg = "Decision: transfer CPU: " + finalCpu + ", Memory: " + finalMem + " from " + giver + "M to " + taker;
        LOG.info(msg);

        shrinks.put(giver, Resource.newInstance(giverConfig.getMemory() - finalMem, giverConfig.getVirtualCores() - finalCpu));
        expands.put(taker, Resource.newInstance(takerConfig.getMemory() + finalMem,  takerConfig.getVirtualCores() + finalCpu));
        return true;
    }

    private boolean diagnose_v2(Examiner examiner){
        String giver = "000002";
        String taker = "000003";
        int giverMem = 0, takerMem = 0;

        if(examiner.getInstantDelay().get(giver) == examiner.getInstantDelay().get(taker)){
            return false;
        } else if(examiner.getInstantDelay().get(giver) > examiner.getInstantDelay().get(taker)){
            giver = "000003";
            taker = "000002";
        }
        int giverState = examiner.resourceInfo.executorResourceState.get(giver);
        int takerState = examiner.resourceInfo.executorResourceState.get(taker);
//        System.out.println("GiverState: " + giverState);
//        System.out.println("TakerState: " + takerState);
        if(giverState == 0 && takerState == 0){
//            System.out.println("both idle");
            if (!examiner.resourceInfo.executorDeltaMem.containsKey(giver) || !examiner.resourceInfo.executorDeltaMem.containsKey(taker))
                return false;
            int giverFree = examiner.resourceInfo.executorConfigMem.get(giver) - examiner.resourceInfo.executorUsedMem.get(giver).intValue();
            int takerFree = examiner.resourceInfo.executorConfigMem.get(taker) - examiner.resourceInfo.executorUsedMem.get(taker).intValue();
            //TODO: if delta mem < 0
            if(examiner.resourceInfo.executorDeltaMem.get(giver) + examiner.resourceInfo.executorDeltaMem.get(taker) <= 0)
                return false;
//            System.out.println("delta mem: " + examiner.resourceInfo.executorDeltaMem);
            Double giverTargetFree = ((examiner.resourceInfo.executorDeltaMem.get(giver))/(examiner.resourceInfo.executorDeltaMem.get(giver) + examiner.resourceInfo.executorDeltaMem.get(taker))
                    * (giverFree + takerFree - 60)) +  30;
            Double takerTargetFree = giverFree + takerFree - giverTargetFree;
//            System.out.println("Target giver free: " + giverTargetFree);
//            System.out.println("Target taker free: " + takerTargetFree);
//            System.out.println("giver free: " + giverFree);
//            System.out.println("taker free: " + takerFree);
            if (giverTargetFree + examiner.resourceInfo.executorUsedMem.get(giver) <= 300) {
                giverTargetFree = 300.0 - examiner.resourceInfo.executorUsedMem.get(giver);
                takerTargetFree = giverFree + takerFree - giverTargetFree;
            } else if(takerTargetFree + examiner.resourceInfo.executorUsedMem.get(taker) <= 300) {
                takerTargetFree = 300.0 - examiner.resourceInfo.executorUsedMem.get(taker);
                giverTargetFree = giverFree + takerFree - takerTargetFree;
            }
            giverMem = giverFree - giverTargetFree.intValue();
            takerMem = -giverMem;
        } else if(giverState == 1 && takerState == 0){
            return false;
//            takerMem = min((examiner.resourceInfo.executorConfigMem.get(taker) - examiner.resourceInfo.executorUsedMem.get(taker).intValue())/2,
//                    examiner.resourceInfo.executorConfigMem.get(taker) - 300);
//            giverMem = -takerMem;
        } else if(giverState == 0 && takerState == 1){
            giverMem = min((examiner.resourceInfo.executorConfigMem.get(giver) - examiner.resourceInfo.executorUsedMem.get(giver).intValue())/2,
                    examiner.resourceInfo.executorConfigMem.get(giver) - 300);
            takerMem = -giverMem;
        } else if(giverState == 1 && takerState == 1){
            return false;
//            giverMem = 100;
//            takerMem = -100;
        }
        String msg = "before transition, taker " + taker + " has Memory: " + examiner.resourceInfo.executorConfigMem.get(taker) + "M.";
        LOG.info(msg);
        msg = "before transition, giver " + giver + " has Memory: " + examiner.resourceInfo.executorConfigMem.get(giver) + "M.";
        LOG.info(msg);
        msg = "Decision: transfer Memory: " + giverMem + "M from " + giver + " to " + taker;
        LOG.info(msg);

        if(giverMem > 0) {
            shrinks.put(giver, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(giver) - giverMem, examiner.resourceInfo.executorConfigCpu.get(giver)));
            expands.put(taker, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(taker) + giverMem, examiner.resourceInfo.executorConfigCpu.get(taker)));
        } else{
            expands.put(giver, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(giver) - giverMem, examiner.resourceInfo.executorConfigCpu.get(giver)));
            shrinks.put(taker, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(taker) + giverMem, examiner.resourceInfo.executorConfigCpu.get(taker)));
        }
        return true;
    }

    private boolean diagnose_mem(Examiner examiner){
        String giver = "000002";
        String taker = "000003";
        int giverMem = 0, takerMem = 0;

        LOG.info("Instant Delay: " + examiner.getInstantDelay());

        if(examiner.getInstantDelay().get(giver) == examiner.getInstantDelay().get(taker)){
            return false;
        } else if(examiner.getInstantDelay().get(giver) > examiner.getInstantDelay().get(taker)){
            giver = "000003";
            taker = "000002";
        }
        Resource giverConfig = examiner.resources.get(giver);
        Resource takerConfig = examiner.resources.get(taker);
        if (examiner.memUsed.get(taker) >= takerConfig.getMemory())
            takerMem = 100;
        else
            return false;

        if (giverConfig.getMemory() <= 300)
            return false;
        else if (giverConfig.getMemory() > examiner.memUsed.get(giver))
            giverMem = giverConfig.getMemory() - examiner.memUsed.get(giver).intValue();
        else
            giverMem = giverConfig.getMemory() - 300;
        int finalMem = min(takerMem, giverMem);
        if (finalMem == 0)
            return false;

        String msg = "before transition, taker " + taker + " has Memory: " + takerConfig.getMemory() + "M.";
        LOG.info(msg);
        msg = "before transition, giver " + giver + " has Memory: " + giverConfig.getMemory() + "M.";
        LOG.info(msg);
        msg = "Decision: transfer Memory: " + finalMem + " from " + giver + "M to " + taker;
        LOG.info(msg);

        shrinks.put(giver, Resource.newInstance(giverConfig.getMemory() - finalMem, giverConfig.getVirtualCores()));
        expands.put(taker, Resource.newInstance(takerConfig.getMemory() + finalMem,  takerConfig.getVirtualCores()));
        return true;

    }

    private boolean diagnose_v3_cpu(Examiner examiner){
        String container = "000002";
        if (startTime == 0) {
            startTime = System.currentTimeMillis();
            return false;
        }
        long now = System.currentTimeMillis();
        if((now - startTime) > 30000){
            startTime = now;
            int cpu = examiner.resourceInfo.executorConfigCpu.get(container);
            int targetCpu = 0;
            if (cpu == 4){
                targetCpu = 3;
                isIncrease = false;
            } else if (cpu == 1){
                targetCpu = 2;
                isIncrease = true;
            } else if (cpu == 2 || cpu == 3){
                if (isIncrease)
                    targetCpu = cpu + 1;
                else
                    targetCpu = cpu - 1;
            }
            if (isIncrease)
                expands.put(container, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(container) , targetCpu));
            else
                shrinks.put(container, Resource.newInstance(examiner.resourceInfo.executorConfigMem.get(container) , targetCpu));
            return true;
        }
        return false;
    }

    private boolean diagnose_v3_mem(Examiner examiner){
        String container = "000002";
        if(startTime == 0){
            startTime = System.currentTimeMillis();
            return false;
        }
        long now = System.currentTimeMillis();
        if((now - startTime) > 60000 && (now - startTime) < 62000){
            startTime = now;
            int targetMem = 0;
            int mem = examiner.resourceInfo.executorConfigMem.get(container);
            if (mem >= 2000)
                isIncrease = false;
            if (mem <= 1000)
                isIncrease = true;
            if(!isIncrease){
                targetMem = mem - 300;
            } else {
                targetMem = mem + 0;
            }
            if (isIncrease)
                expands.put(container, Resource.newInstance(targetMem, examiner.resourceInfo.executorConfigCpu.get(container)));
            else
                shrinks.put(container, Resource.newInstance(targetMem, examiner.resourceInfo.executorConfigCpu.get(container)));
            return true;
        }
        return false;
    }

    private int min(int num1, int num2){
        if(num1 < num2)
            return num1;
        else return num2;
    }

    //Main logic:  examine->diagnose->treatment->sleep
    void work(long timeIndex) {
        LOG.info("Examine...");
        //Examine
        boolean stateValidity = examine(timeIndex);
        if (resourceChecker.isSleeping()) {

            //Check is started
//            if (!isStarted) {
//                LOG.info("Check started...");
//                for (int id : examiner.state.substreamStates.keySet()) {
//                    if (examiner.state.substreamStates.get(id).arrived.containsKey(timeIndex) && examiner.state.substreamStates.get(id).arrived.get(timeIndex) != null && examiner.state.substreamStates.get(id).arrived.get(timeIndex) > 0) {
//                        isStarted = true;
//                        break;
//                    }
//                }
//            }
//
//            if (timeIndex > 200 && stateValidity && isStarted) {
//                shrinks.clear();
//                expands.clear();
//                if (diagnose_v3_mem(examiner)) {
//                    resourceChecker.startAdjust(shrinks, expands);
//                }
//            }
        }

//        if(stateValidity && isStarted && resizeStatus == 0){
//            expands.clear();
//            shrinks.clear();
//            LOG.info("Diagnose...");
//            Map<String, Map<String, Long>> assignment = getReallocation(examiner);
//            for(Map.Entry<String, Long> entry : assignment.get("Expand").entrySet()){
//                expands.put(entry.getKey(), entry.getValue());
//            }
//            for(Map.Entry<String, Long> entry : assignment.get("Shrink").entrySet()){
//                shrinks.put(entry.getKey(), entry.getValue());
//            }
//            if (shrinks.size() > 0){
//                for (Map.Entry<String, Long> entry : shrinks.entrySet())
//                    listener.containerResize(entry.getKey(), 1, entry.getValue().intValue());
//            }
//            if (expands.size() > 0){
//                for (Map.Entry<String, Long> entry : shrinks.entrySet())
//                    listener.containerResize(entry.getKey(), 1, entry.getValue().intValue());
//            }
//        }

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
