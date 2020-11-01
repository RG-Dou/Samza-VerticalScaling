package org.apache.samza.job.model;

import org.apache.samza.config.Config;

import java.util.Map;

public class ResponseModel extends JobModel{

    private final String processorId;
    private final String response;

    public ResponseModel(String processorId, String response, Config config, Map<String, ContainerModel> containers){
        super(config, containers);
        this.processorId = processorId;
        this.response = response;
    }

    public ResponseModel(String processorId, String response, JobModel jobModel){
        super(jobModel.getConfig(), jobModel.getContainers());
        this.processorId = processorId;
        this.response = response;
    }

//    public ResponseModel(Config config, Map<String, ContainerModel> containers) {
//        super(config, containers);
//    }

    public String getProcessorId() {return processorId;}

    public String getResponse(){
        return response;
    }
}
