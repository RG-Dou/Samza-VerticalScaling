package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.samza.SamzaException;
import org.apache.samza.job.yarn.ClientHelper;
import org.apache.samza.util.hadoop.HttpFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class YarnContainerMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.YarnContainerMetrics.class);
    private final YarnClient client;
    private final String jobName;

    private ApplicationId appId;
    private ApplicationAttemptId attemptId;


    public YarnContainerMetrics(String jobName){
        YarnConfiguration hadoopConfig = new YarnConfiguration();
        hadoopConfig.set("fs.http.impl", HttpFileSystem.class.getName());
        hadoopConfig.set("fs.https.impl", HttpFileSystem.class.getName());
        client = new ClientHelper(hadoopConfig).yarnClient();
        this.jobName = jobName;
    }

    private void validateAppId() throws Exception {
        // fetch only the last created application with the job name and id
        // i.e. get the application with max appId
        for(ApplicationReport applicationReport : this.client.getApplications()) {
            //TODO: not container, equals instead
            if(applicationReport.getName().contains(this.jobName)) {
                ApplicationId id = applicationReport.getApplicationId();
                if(appId == null || appId.compareTo(id) < 0) {
                    appId = id;
                }
            }
        }
        if (appId != null) {
            LOG.info("Job lookup success. ApplicationId " + appId.toString());
        } else {
            throw new SamzaException("Job lookup failure " + this.jobName);
        }
    }

    private void validateRunningAttemptId() throws Exception {
        attemptId = this.client.getApplicationReport(appId).getCurrentApplicationAttemptId();
        ApplicationAttemptReport attemptReport = this.client.getApplicationAttemptReport(attemptId);
        if (attemptReport.getYarnApplicationAttemptState() == YarnApplicationAttemptState.RUNNING) {
            LOG.info("Job is running. AttempId " + attemptId.toString());
        } else {
            throw new SamzaException("Job not running " + this.jobName + ", App id " + this.appId + ", AttemptId " + this.attemptId + ", State " + attemptReport.getYarnApplicationAttemptState());
        }
    }

    public Map<String, Resource> getAllMetrics(){
        if (appId == null || attemptId == null){
            try {
                validateAppId();
                validateRunningAttemptId();
//            log.info("Start validating job " + this.jobName);
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                System.exit(1);
            }
        }


        HashMap<String, Resource> info = new HashMap<String, Resource>();
        try {
            for(ContainerReport containerReport : this.client.getContainers(attemptId)) {
                if (containerReport.getContainerState() == ContainerState.RUNNING) {
                    String containerID = containerReport.getContainerId().toString();
                    Resource resource = containerReport.getAllocatedResource();
                    //TODO: not substring.
                    info.put(containerID.substring(containerID.length() - 6, containerID.length()), resource);
                }
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            System.exit(1);
        }
        return info;
    }

    public Resource getOneMetrics(String containerIdStr){
        Resource resource = null;
        ContainerId containerId = ContainerId.newContainerId(attemptId, Long.parseLong(containerIdStr));
        try {
            ContainerReport containerReport = client.getContainerReport(containerId);
            if (containerReport.getContainerState() == ContainerState.RUNNING) {
                resource = containerReport.getAllocatedResource();
            }
        } catch (Exception e){
            LOG.error(e.getMessage(), e);
            System.exit(1);
        }
        return resource;
    }

}
