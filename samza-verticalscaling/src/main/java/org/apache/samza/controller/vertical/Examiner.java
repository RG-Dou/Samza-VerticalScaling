package org.apache.samza.controller.vertical;
import org.apache.hadoop.yarn.api.records.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.samza.config.Config;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Examiner{
    //Model here is only a snapshot
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.Examiner.class);
    public Model model;
    public State state;
    public Examiner(Config config, long metricsRetreiveInterval){
        state = new State(config, metricsRetreiveInterval);
        model = new Model(state, config, metricsRetreiveInterval);
    }

    public void init(Map<String, List<String>> executorMapping){
        state.init(executorMapping);
    }

    public boolean updateState(long timeIndex, Map<String, Long> substreamArrived, Map<String, Long> substreamProcessed, Map<String, Boolean> substreamValid, Map<String, List<String>> executorMapping){
        LOG.info("Updating state...");
        state.insert(timeIndex, substreamArrived, substreamProcessed, executorMapping);
        state.calibrate(substreamValid);
        state.drop(timeIndex, substreamValid);

        //Debug & Statistics
//        if(true){
//            System.out.println("Model, time " + timeIndex  + " , executors completed: " + model.executorCompleted);
//            System.out.println("Model, time " + timeIndex  + " , executors arrived: " + model.executorArrived);
//            System.out.println("Model, time " + timeIndex  + " , average cpu usage: " + state.executorState.avgCpuUsage);
//        }

        //Debug & Statistics
        HashMap<Integer, Long> arrived = new HashMap<>(), completed = new HashMap<>();
        for(int substream: state.substreamStates.keySet()) {
            arrived.put(substream, state.substreamStates.get(substream).arrived.get(state.getCurrentTimeIndex()));
            completed.put(substream, state.substreamStates.get(substream).completed.get(state.getCurrentTimeIndex()));
        }
        if(state.checkValidity(substreamValid)){
            //state.calculate(timeIndex);
            //state.drop(timeIndex);
            return true;
        }else return false;
    }

    public void updateModel(long timeIndex, Map<String, Double> serviceRate, Map<String, List<String>> executorMapping){
//        if(timeIndex > 5000)
        model.updateRegressionModel(timeIndex);
        model.updateValidRatio(timeIndex);
        model.update(timeIndex, serviceRate, executorMapping);
        //Debug & Statistics
        if(true){
//            HashMap<String, Double> longtermDelay = new HashMap<>();
//            for(String executorId: executorMapping.keySet()){
//                double delay = model.getLongTermDelay(executorId);
//                longtermDelay.put(executorId, delay);
//            }
            System.out.println("Model, time " + timeIndex  + " , Arrival Rate: " + model.executorArrivalRate);
            System.out.println("Model, time " + timeIndex  + " , processing Rate: " + model.executorProcessingRate);
            System.out.println("Model, time " + timeIndex  + " , average processing Rate: " + model.avgProcessingRate);
            System.out.println("Model, time " + timeIndex  + " , average arrival Rate: " + model.avgArrivalRate);
            System.out.println("Model, time " + timeIndex  + " , Service Rate: " + model.executorServiceRate);
            System.out.println("Model, time " + timeIndex  + " , Instantaneous Delay: " + model.executorInstantaneousDelay);
            System.out.println("Model, time " + timeIndex  + " , Processed Arrival Rate: " + model.executorArrivalRateInDelay);
            System.out.println("Model, time " + timeIndex  + " , Max Major Fault: " + model.maxMPFPerCpu);
            System.out.println("Model, time " + timeIndex  + " , Valid Rate: " + model.validRate);
//                System.out.println("Model, time " + timeIndex  + " , Longterm Delay: " + longtermDelay);
//            System.out.println("Model, time " + timeIndex  + " , Partition Arrival Rate: " + model.substreamArrivalRate);
        }
    }
//    private Map<String, Double> getInstantDelay(){
//        return model.executorInstantaneousDelay;
//    }
//
//    private Map<String, Double> getLongtermDelay(){
//        HashMap<String, Double> delay = new HashMap<>();
//        for(String executor: state.executorMapping.keySet()){
//            delay.put(executor, model.getLongTermDelay(executor));
//        }
//        return delay;
//    }

    //DrG
    public void updateExecutorState(long timeIndex, Map<String, Resource> executorResource, Map<String, Double> executorMemUsed,
                                    Map<String, Double> executorCpuUsage, Map<String, Long> pgMajFault){
        this.state.executorState.updateResource(timeIndex, executorResource, executorMemUsed, executorCpuUsage, pgMajFault);
        System.out.println("Model, time " + timeIndex  + ", executor configure resource: " + executorResource);
        System.out.println("Model, time " + timeIndex  + ", executor used memory: " + executorMemUsed);
        System.out.println("Model, time " + timeIndex  + ", executor cpu usages: " + executorCpuUsage);
    }
}
