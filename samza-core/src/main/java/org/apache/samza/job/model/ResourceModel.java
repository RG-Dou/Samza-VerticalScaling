package org.apache.samza.job.model;

import org.apache.samza.config.Config;
import org.apache.samza.container.LocalityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceModel extends JobModel {

    private final Map<String, Long> memModels;

    public ResourceModel(Map<String, Long> memModels, Config config, Map<String, ContainerModel> containers){
        super(config, containers);
        this.memModels = memModels;
    }

    public ResourceModel(Map<String, Long> memModel, JobModel jobModel){
        super(jobModel.getConfig(), jobModel.getContainers());
        this.memModels = memModel;
    }

    public Long getMemFromProcessor(String processorId){
        return memModels.get(processorId);
    }

    public Map<String, Long> getMemModels(){
        return memModels;
    }
}
