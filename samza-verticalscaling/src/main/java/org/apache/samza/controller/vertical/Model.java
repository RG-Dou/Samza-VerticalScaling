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
    Map<String, Double> substreamArrivalRate, executorArrivalRate, executorServiceRate, executorInstantaneousDelay, executorArrivalRateInDelay; //Longterm delay could be calculated from arrival rate and service rate
    Map<String, Long> executorCompleted, executorArrived; //For debugging instant delay
    Map<String, Double> processingRate, avgProcessingRate, avgArrivalRate;

    public Map<String, Double> validRate;
    private SimpleRegression regression = new SimpleRegression();
    public double maxMPFPerCpu = 4000.0;

    private final double initialServiceRate, decayFactor, conservativeFactor; // Initial prediction by user or system on service rate.
    private final long metricsRetreiveInterval, windowReq;
    public Model(State state, Config config, long metricsRetreiveInterval, long windowReq){
        substreamArrivalRate = new HashMap<>();
        executorArrivalRate = new HashMap<>();
        executorServiceRate = new HashMap<>();
        executorInstantaneousDelay = new HashMap<>();
        executorArrivalRateInDelay = new HashMap<>();
        executorCompleted = new HashMap<>();
        executorArrived = new HashMap<>();
        processingRate = new HashMap<>();
        avgProcessingRate = new HashMap<>();
        avgArrivalRate = new HashMap<>();
        validRate = new HashMap<>();

        this.state = state;
        initialServiceRate = config.getDouble("streamswitch.system.initialservicerate", 0.2);
        decayFactor = config.getDouble("streamswitch.system.decayfactor", 0.875);
        conservativeFactor = config.getDouble("streamswitch.system.conservative", 1.0);

        this.metricsRetreiveInterval = metricsRetreiveInterval;
        this.windowReq = windowReq;
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
        long n0 = timeIndex - windowReq + 1;
        if(n0<1){
            n0 = 1;
            LOG.warn("Calculate instant delay index smaller than window size!");
        }
        for(long i = n0; i <= timeIndex; i++){
            if(state.getMapping(i).containsKey(state.executorIdFromStringToInt(executorId))){
                for(int substream: state.getMapping(i).get(state.executorIdFromStringToInt(executorId))){
                    totalCompleted += state.getSubstreamCompleted(substream, i) - state.getSubstreamCompleted(substream, i - 1);
                    totalDelay += state.getSubstreamLatency(substream, i);
                }
            }
        }
        //In state, latency is count as # of timeslots, need to transfer to real time
        if(totalCompleted > 0) return totalDelay * metricsRetreiveInterval / ((double)totalCompleted);
        return 0;
    }

    private double arrivalRateInInstantDelay(String executorId, long timeIndex){
        double totalArrivalRate = 0.0;
        long n0 = timeIndex - windowReq + 1;
        if(n0<1){
            n0 = 1;
            LOG.warn("Calculate instant delay index smaller than window size!");
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
                long deltaTuples = state.getSubstreamArrived(substream, endIndex) - state.getSubstreamArrived(substream, firstIndex);
                totalArrivalRate += deltaTuples/(endIndex - firstIndex + 1);
            }
        }
        return totalArrivalRate;
    }

    public void updateInformation(long timeIndex){
        executorCompleted.clear();
        executorArrived.clear();
        processingRate.clear();
        avgProcessingRate.clear();
        avgArrivalRate.clear();

        for(String executorId: state.executorMapping.keySet()) {
            long n0 = timeIndex - windowReq + 1;
            long totalCompleted = 0;
            long totalArrived = 0;
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
                        totalArrived += state.getSubstreamArrived(substream, i) - state.getSubstreamArrived(substream, i - 1);
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

            executorCompleted.put(executorId, totalCompleted);
            executorArrived.put(executorId, totalArrived);
            processingRate.put(executorId, nowCompleted / (metricsRetreiveInterval / 1000.0));
            avgProcessingRate.put(executorId, totalCompleted / (timeIndex - n0 + 1) / (metricsRetreiveInterval / 1000.0));
            avgArrivalRate.put(executorId, totalArrived / (timeIndex - n0 + 1) / (metricsRetreiveInterval / 1000.0));


            if(timeIndex >= 2 * windowReq) {
                regression.addData(prPerCpu, pgFaultPerCpu);
//                maxMPFPerCpu = regression.getIntercept();
                validRate.put(executorId, 1 - pgFaultPerCpu / maxMPFPerCpu);
            }
        }
    }

    //Calculate model snapshot from state
    public void update(long timeIndex, Map<String, Double> serviceRate, Map<String, List<String>> executorMapping){
//                LOG.info("Updating model snapshot, clear old data...");
        //substreamArrivalRate.clear();
        executorArrivalRate.clear();
        //executorInstantaneousDelay.clear();
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

            double oldArrivalRateInDelay = executorArrivalRateInDelay.getOrDefault(executor, 0.0);
            double newArrivalRateInDelay = oldArrivalRateInDelay * decayFactor + arrivalRateInInstantDelay(executor, timeIndex) * (1.0 - decayFactor);
            executorArrivalRateInDelay.put(executor, newArrivalRateInDelay);
//                    updateProcessingRate(executor, timeIndex);
        }
        //Debugging
//                LOG.info("Debugging, avg utilization: " + utils);
//                LOG.info("Debugging, partition arrival rate: " + substreamArrivalRate);
//                LOG.info("Debugging, executor avg service rate: " + executorServiceRate);
        LOG.info("Debugging, executor avg delay: " + executorInstantaneousDelay);
    }

    public Map<String, Double> getArrivalRateInDelay(){
        return executorArrivalRateInDelay;
    }

    public Map<String, Double> getInstantDelay(){
        return executorInstantaneousDelay;
    }
}