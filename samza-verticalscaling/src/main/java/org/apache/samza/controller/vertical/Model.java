package org.apache.samza.controller.vertical;

import org.apache.samza.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.math3.stat.regression.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Model here is only a snapshot
public class Model {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.Model.class);
    private final State state;
    Map<String, Double> substreamArrivalRate, substreamProcessingRate, executorArrivalRate, executorServiceRate, executorInstantaneousDelay, executorArrivalRateInDelay, executorProcessingRate; //Longterm delay could be calculated from arrival rate and service rate
//    Map<String, Long> executorCompleted, executorArrived; //For debugging instant delay
    Map<String, Double> processingRate, avgProcessingRate, avgArrivalRate, pRatePerCpu;
    Map<Long, ModelData> modelDatas;

    public Map<String, Double> validRate;
    public double maxMPFPerCpu = 100.0;
    private final long winMetrics, winRegression, winPGFault;

    private final double initialServiceRate, decayFactor, conservativeFactor; // Initial prediction by user or system on service rate.
    private final long metricsRetreiveInterval;
    public Model(State state, Config config, long metricsRetreiveInterval){
        substreamArrivalRate = new HashMap<>();
        substreamProcessingRate = new HashMap<>();
        executorArrivalRate = new HashMap<>();
        executorProcessingRate = new HashMap<>();
        executorServiceRate = new HashMap<>();
        executorInstantaneousDelay = new HashMap<>();
        executorArrivalRateInDelay = new HashMap<>();
//        executorCompleted = new HashMap<>();
//        executorArrived = new HashMap<>();
        processingRate = new HashMap<>();
        avgProcessingRate = new HashMap<>();
        avgArrivalRate = new HashMap<>();
        validRate = new HashMap<>();
        modelDatas = new HashMap<>();
        pRatePerCpu = new HashMap<>();

        this.state = state;
        initialServiceRate = config.getDouble("streamswitch.system.initialservicerate", 0.2);
        decayFactor = config.getDouble("streamswitch.system.decayfactor", 0.875);
        conservativeFactor = config.getDouble("streamswitch.system.conservative", 1.0);
        this.winMetrics = config.getLong("verticalscaling.window.metrics", 100)/metricsRetreiveInterval;
        this.metricsRetreiveInterval = metricsRetreiveInterval;

        this.winPGFault = config.getLong("verticalscaling.window.pgfault", 2000)/metricsRetreiveInterval;
        this.winRegression = config.getLong("verticalscaling.window.regression", 2000) / metricsRetreiveInterval; //Unit: # of time slots;
    }

    private double calculateLongTermDelay(double arrival, double service){
        // Conservative !
        service = conservativeFactor * service;
        if(arrival < 1e-15)return 0.0;
        if(service < arrival + 1e-15)return 1e100;
        return 1.0/(service - arrival);
    }

    // 1 / ( u - n ). Return  1e100 if u <= n
    public double getLongTermDelay(String executorId){
        double arrival = executorArrivalRate.get(executorId);
        double service = executorServiceRate.get(executorId);
        return calculateLongTermDelay(arrival, service);
    }

    private double calculateSubstreamProcessingRate(String substream, long n0, long n1){
        if(n0 < 0)n0 = 0;
        long time = state.getTimepoint(n1) - state.getTimepoint(n0);
        long totalCompleted = state.getSubstreamCompleted(state.substreamIdFromStringToInt(substream), n1) - state.getSubstreamCompleted(state.substreamIdFromStringToInt(substream), n0);
        if(time > 1e-9)return totalCompleted / ((double)time);
        return 0.0;
    }

    private double calculateSubstreamArrivalRate(String substream, long n0, long n1){
        if(n0 < 0)n0 = 0;
        long time = state.getTimepoint(n1) - state.getTimepoint(n0);
        long totalArrived = state.getSubstreamArrived(state.substreamIdFromStringToInt(substream), n1) - state.getSubstreamArrived(state.substreamIdFromStringToInt(substream), n0);
        if(time > 1e-9)return totalArrived / ((double)time);
        return 0.0;
    }

    private double calculateSubstreamArrivalRateInt(int substream, long n0, long n1){
        if(n0 < 0)n0 = 0;
        long time = state.getTimepoint(n1) - state.getTimepoint(n0);
        long totalArrived = state.getSubstreamArrived(substream, n1) - state.getSubstreamArrived(substream, n0);
        if(time > 1e-9)return totalArrived / ((double)time);
        return 0.0;
    }

    private double calculateSubstreamSmoothArrivalRate(int substream, long n0, long n1){
        if(n0 < 0)n0 = 0;
        long time = state.getTimepoint(n1) - state.getTimepoint(n0);
        double totalArrived = state.getSubstreamSmoothArrived(substream, n1) - state.getSubstreamSmoothArrived(substream, n0);
//        if(substream == 3)
//            System.out.println("Start epoch: " + n0 + ", tuples: " + state.getSubstreamSmoothArrived(substream, n0) + "end epoch: " + n1 + ", tuples: " + state.getSubstreamSmoothArrived(substream, n1));
        if(time > 1e-9)return totalArrived / ((double)time);
        return 0.0;
    }

    //Window average delay
//    private double calculateExecutorInstantaneousDelay(String executorId, long timeIndex){
//        long totalDelay = 0;
//        long totalCompleted = 0;
//        long n0 = timeIndex - windowReq + 1;
//        if(n0<1){
//            n0 = 1;
//            LOG.warn("Calculate instant delay index smaller than window size!");
//        }
//        for(long i = n0; i <= timeIndex; i++){
//            if(state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))){
//                for(int substream: state.getMapping(i).get(state.executorIdFromStringToInt(executorId))){
//                    totalCompleted += state.getSubstreamCompleted(substream, i) - state.getSubstreamCompleted(substream, i - 1);
//                    totalDelay += state.getSubstreamLatency(substream, i);
//                }
//            }
//        }
//        //In state, latency is count as # of timeslots, need to transfer to real time
//        if(totalCompleted > 0) return totalDelay * metricsRetreiveInterval / ((double)totalCompleted);
//        return 0;
//    }


    //Window average delay
    private double calculateExecutorInstantaneousDelay(String executorId, long timeIndex){
        long totalDelay = 0;
        long totalCompleted = 0;
        if(state.getMapping(timeIndex).containsKey(state.executorIdFromStringToInt(executorId))){
            for(int substream: state.getMapping(timeIndex).get(state.executorIdFromStringToInt(executorId))){
                totalCompleted += state.getSubstreamCompleted(substream, timeIndex) - state.getSubstreamCompleted(substream, timeIndex - 1);
                totalDelay += state.getSubstreamLatency(substream, timeIndex);
            }
        }

        //In state, latency is count as # of timeslots, need to transfer to real time
        if(totalCompleted > 0) return totalDelay * metricsRetreiveInterval / ((double)totalCompleted);
        return 0;
    }


    private double processedArrivalRate(String executorId, long timeIndex){
        double totalArrivalRate = 0.0;
        long n0 = timeIndex - winMetrics + 1;
        if(n0<1){
            n0 = 1;
            LOG.warn("Calculate instant delay index smaller than window size!");
        }
        if(state.getMapping(timeIndex) == null)
            return 0.0;
        else{
            Map<Integer, List<Integer>> mapping = state.getMapping(timeIndex);
            if(!mapping.containsKey(state.executorIdFromStringToInt(executorId)))
                return 0.0;
        }
        for(int substream: state.getMapping(timeIndex).get(state.executorIdFromStringToInt(executorId))){
            long firstIndex = 0l;
            long endIndex = 0l;
            for (long i = n0; i <= timeIndex; i++){
                if(state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))){
                    firstIndex = state.getFirstIndex(substream, i);
                    if(firstIndex != 0)
                        break;
                }
            }
            for (long i = timeIndex; i >= n0; i--){
                if(state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))){
                    endIndex = state.getEndIndex(substream, i);
                    if(endIndex != 0)
                        break;
                }
            }
            if(firstIndex != 0l && endIndex != 0l){
                double arrivalRate = calculateSubstreamSmoothArrivalRate(substream, firstIndex - 1, endIndex);
//                double arrivalRate = calculateSubstreamArrivalRateInt(substream, firstIndex - 1, endIndex);
                totalArrivalRate += arrivalRate;
            }
        }
        return totalArrivalRate;
    }

    public void updateRegressionModel(long timeIndex){
//        executorCompleted.clear();
//        executorArrived.clear();
        processingRate.clear();
        avgProcessingRate.clear();
        avgArrivalRate.clear();
        pRatePerCpu.clear();
        HashMap<String, Double> pageFaultPerCpu = new HashMap<>();

        for(String executorId: state.executorMapping.keySet()) {
            long n0 = timeIndex - winRegression + 1;
            long totalCompleted = 0;
//            long totalArrived = 0;
            long nowCompleted = 0;
            long firstPGFault = 0L;
            long firstPGIndex = 0L;
            double totalCPUUsage = 0.0;
            int cpuUsageCount = 0;
            if (n0 < 1) {
                n0 = 1;
                LOG.warn("Calculate instant delay index smaller than window size!");
            }
//                    if(state.getMapping(timeIndex).containsKey(state.executorIdFromStringToInt(executorId))){
//                        for (int substream : state.getMapping(timeIndex).get(state.executorIdFromStringToInt(executorId))) {
//                            totalCompleted += state.getSubstreamCompleted(substream, timeIndex) - state.getSubstreamCompleted(substream, timeIndex - 1);
//                            totalArrived += state.getSubstreamArrived(substream, timeIndex) - state.getSubstreamArrived(substream, timeIndex - 1);
//                        }
//                    }
            if(!state.getMapping(timeIndex).containsKey(state.executorIdFromStringToInt(executorId))) {
                System.out.println("executor: " + executorId + " is not in the mapping");
                continue;
            }
            for (long i = n0; i <= timeIndex; i++) {
                if (state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))) {
                    for (int substream : state.getMapping(i).get(state.executorIdFromStringToInt(executorId))) {
                        totalCompleted += state.getSubstreamCompleted(substream, i) - state.getSubstreamCompleted(substream, i - 1);
//                        totalArrived += state.getSubstreamArrived(substream, i) - state.getSubstreamArrived(substream, i - 1);
                        if (i == timeIndex)
                            nowCompleted += state.getSubstreamCompleted(substream, i) - state.getSubstreamCompleted(substream, i - 1);
                    }
                    if(state.getCpuUsage(i, executorId) != 0.0) {
                        totalCPUUsage += state.getCpuUsage(i, executorId);
                        cpuUsageCount+=1;
                    }
                    if(firstPGFault == 0 && state.getPGFault(i, executorId) != 0) {
                        firstPGFault = state.getPGFault(i, executorId);
                        firstPGIndex = i;
                    }
                }
            }
            long pgFaultAvg;
            if(firstPGIndex == timeIndex){
                pgFaultAvg = 0;
            } else {
                pgFaultAvg = (state.getPGFault(timeIndex, executorId) - firstPGFault) / (timeIndex - firstPGIndex);
            }
            double cpuUsageAvg = totalCPUUsage/cpuUsageCount;
            double pgFaultPerCpu = pgFaultAvg/cpuUsageAvg;
            double prPerCpu = totalCompleted / (timeIndex - n0 + 1) / cpuUsageAvg;

//            executorCompleted.put(executorId, totalCompleted);
//            executorArrived.put(executorId, totalArrived);
            avgProcessingRate.put(executorId, totalCompleted * 1.0 / (timeIndex - n0 + 1) / metricsRetreiveInterval);
            pageFaultPerCpu.put(executorId, pgFaultPerCpu);
            pRatePerCpu.put(executorId, prPerCpu / metricsRetreiveInterval);

//            regression.addData(prPerCpu, pgFaultPerCpu);
            double intercept = updateModelData(prPerCpu, pgFaultPerCpu);
            if (intercept > 0)
                maxMPFPerCpu = intercept;


        }
        System.out.println("Model, time " + timeIndex  + " , page fault per cpu: " + pageFaultPerCpu);
        System.out.println("Model, time " + timeIndex  + " , pRate per cpu: " + pRatePerCpu);
    }

    private double updateModelData(double prPerCpu, double pgFaultPerCpu){
        SimpleRegression regression = new SimpleRegression();
        long index = (long) Math.floor(pgFaultPerCpu);
        ModelData data = modelDatas.get(index);
        if (data == null){
            data = new ModelData(index);
        }
        data.addData(prPerCpu, pgFaultPerCpu);
        modelDatas.put(index, data);
        for(Map.Entry<Long, ModelData> entry : modelDatas.entrySet()){
            ModelData tmpData = entry.getValue();
            regression.addData(tmpData.getKey(), tmpData.getValue());
        }
        return regression.getIntercept();
    }

    public void updateValidRatio(long timeIndex){
        for(String executorId: state.executorMapping.keySet()) {
            long n0 = timeIndex - winPGFault + 1;
            long firstPGFault = 0L;
            long firstPGIndex = 0L;
            double totalCPUUsage = 0.0;
            int cpuUsageCount = 0;
            if (n0 < 1) {
                n0 = 1;
                LOG.warn("Calculate instant delay index smaller than window size!");
            }
            if(!state.getMapping(timeIndex).containsKey(state.executorIdFromStringToInt(executorId))) {
                System.out.println("executor: " + executorId + " is not in the mapping");
                continue;
            }
            for (long i = n0; i <= timeIndex; i++) {
                if (state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))) {
                    if(state.getCpuUsage(i, executorId) != 0.0) {
                        totalCPUUsage += state.getCpuUsage(i, executorId);
                        cpuUsageCount+=1;
                    }
                    if(firstPGFault == 0 && state.getPGFault(i, executorId) != 0) {
                        firstPGFault = state.getPGFault(i, executorId);
                        firstPGIndex = i;
                    }
                }
            }
            long pgFaultAvg;
            if(firstPGIndex == timeIndex){
                pgFaultAvg = 0;
            } else {
                pgFaultAvg = (state.getPGFault(timeIndex, executorId) - firstPGFault) / (timeIndex - firstPGIndex);
            }
            double cpuUsageAvg = totalCPUUsage/cpuUsageCount;
            double pgFaultPerCpu = pgFaultAvg/cpuUsageAvg;
            if(maxMPFPerCpu > 0)
                validRate.put(executorId, 1 - pgFaultPerCpu / maxMPFPerCpu);
            else
                validRate.put(executorId, 1.0);
        }

    }

    //Calculate model snapshot from state
    public void update(long timeIndex, Map<String, Double> serviceRate, Map<String, List<String>> executorMapping){
//                LOG.info("Updating model snapshot, clear old data...");
        //substreamArrivalRate.clear();
        executorArrivalRate.clear();
        executorProcessingRate.clear();
        //executorInstantaneousDelay.clear();
        for(String executor: executorMapping.keySet()){
            double arrivalRate = 0;
            for(String substream: executorMapping.get(executor)){
//                if(state.getSubstreamSmoothArrived(state.substreamIdFromStringToInt(substream), timeIndex) == 0){
                if(state.getSubstreamSmoothArrived(state.substreamIdFromStringToInt(substream), timeIndex) == 0){
                    arrivalRate = 0;
                    break;
                }
                double oldArrivalRate = substreamArrivalRate.getOrDefault(substream, 0.0);
//                double t = oldArrivalRate * decayFactor + calculateSubstreamArrivalRate(substream, timeIndex - winMetrics, timeIndex) * (1.0 - decayFactor);
                double t = oldArrivalRate * decayFactor + calculateSubstreamSmoothArrivalRate(state.substreamIdFromStringToInt(substream), timeIndex - winMetrics, timeIndex) * (1.0 - decayFactor);
//                if(state.substreamIdFromStringToInt(substream) == 3)
//                    System.out.println("old ar: " + oldArrivalRate + ", new one: " + t);
                substreamArrivalRate.put(substream, t);

//                double t = calculateSubstreamSmoothArrivalRate(state.substreamIdFromStringToInt(substream), timeIndex - 1, timeIndex);

                arrivalRate += t;
            }
            if(arrivalRate > 0)
                executorArrivalRate.put(executor, arrivalRate);

            double processingRate = 0;
            for(String substream: executorMapping.get(executor)){
                double oldProcessRate = substreamProcessingRate.getOrDefault(substream, 0.0);
                if(state.getSubstreamArrived(state.substreamIdFromStringToInt(substream), timeIndex) == 0){
                    processingRate = 0;
                    break;
                }
                double t = oldProcessRate * decayFactor + calculateSubstreamProcessingRate(substream, timeIndex - winMetrics, timeIndex) * (1.0 - decayFactor);
                substreamProcessingRate.put(substream, t);
                processingRate += t;
            }
            if(processingRate > 0)
                executorProcessingRate.put(executor, processingRate);

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
//            double newInstantaneousDelay = calculateExecutorInstantaneousDelay(executor, timeIndex);
            executorInstantaneousDelay.put(executor, newInstantaneousDelay);

            double oldArrivalRateInDelay = executorArrivalRateInDelay.getOrDefault(executor, 0.0);
            double newArrivalRateInDelay = oldArrivalRateInDelay * decayFactor + processedArrivalRate(executor, timeIndex) * (1.0 - decayFactor);
//            double newArrivalRateInDelay = processedArrivalRate(executor, timeIndex);
            if (newArrivalRateInDelay > 0)
                executorArrivalRateInDelay.put(executor, newArrivalRateInDelay);
//                    updateProcessingRate(executor, timeIndex);
        }
        //Debugging
//                LOG.info("Debugging, avg utilization: " + utils);
//                LOG.info("Debugging, partition arrival rate: " + substreamArrivalRate);
//        LOG.info("Debugging, executor avg service rate: " + executorServiceRate);
//        LOG.info("Debugging, executor avg delay: " + executorInstantaneousDelay);
    }

    public Map<String, Double> getArrivalRateInDelay(){
        return executorArrivalRateInDelay;
    }

    public Map<String, Double> getArrivalRate(){
        return executorArrivalRate;
    }

    public Map<String, Double> getInstantDelay(){
        return executorInstantaneousDelay;
    }

    public Map<String, Double> getpRatePerCpu() { return pRatePerCpu;}
}