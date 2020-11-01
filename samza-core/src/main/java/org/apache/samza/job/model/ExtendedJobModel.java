package org.apache.samza.job.model;

import org.apache.samza.config.Config;
import org.apache.samza.container.LocalityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * The data model used to represent a Samza job with a resource mapping. The model is used in the job
 * coordinator and SamzaContainer to determine how to resize the store memory.
 * </p>
 *
 * <p>
 * The hierarchy for a Samza's extended job data model is that each container has a target memory size.
 * </p>
 */
public class ExtendedJobModel extends JobModel {

    private final Map<String, Long> memModels;

    public ExtendedJobModel(Map<String, Long> memModels, Config config, Map<String, ContainerModel> containers){
        super(config, containers);
        this.memModels = memModels;
    }

    public ExtendedJobModel(Map<String, Long> memModel, JobModel jobModel){
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
