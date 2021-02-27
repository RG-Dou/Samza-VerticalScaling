package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.samza.SamzaException;
import org.apache.samza.job.yarn.ClientHelper;
import org.apache.samza.util.hadoop.HttpFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class YarnContainerMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.YarnContainerMetrics.class);
    private final YarnClient client;
    private final String jobName;

    private ApplicationId appId;
    private ApplicationAttemptId attemptId;

    private static Map<String, String> contaienrIdMap = new HashMap<String, String>();

    private final String cgroupMemDir = "/sys/fs/cgroup/memory/yarn/";
    private final String memStat = "memory.stat";
    private final String cgroupCPUDir = "/sys/fs/cgroup/cpu,cpuacct/yarn/";
    private final String cpuStat = "cpuacct.stat";

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
                    String containerIDShort = null;
                    for(Map.Entry<String, String> entry: contaienrIdMap.entrySet()){
                        if (entry.getValue().equals(containerID))
                            containerIDShort = entry.getKey();
                    }
                    if(containerIDShort == null) {
                        continue;
                    }
                    info.put(containerIDShort, resource);
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

    public String getFullContainerId(String containerId){
        if (contaienrIdMap.containsKey(containerId)){
            return contaienrIdMap.get(containerId);
        } else{
            return null;
        }
    }


    public HashMap<String, Long> getMemMetrics(String key){
        HashMap<String, Long> memMetrics = new HashMap<>();
        for (Map.Entry<String, String> entry : contaienrIdMap.entrySet()){
            if(entry.getKey() == "000001")
                continue;
            String fileName = cgroupMemDir + entry.getValue() + "/" + memStat;
            HashMap<String, Long> stat = getStat(fileName);
            if(stat == null)
                continue;
            memMetrics.put(entry.getKey(), stat.get(key));
        }
        return memMetrics;
    }


    private HashMap<String, Long> getStat(String fileName){
        HashMap<String, Long> memStat = new HashMap<>();
        String content = getCGroupParam(fileName);
        if (content == null)
            return  null;
        String[] arrays = content.split("\n");
        for(String pair : arrays){
            String[] pairs = pair.split(" ");
            String key = pairs[0];
            Long value = Long.parseLong(pairs[1]);
            memStat.put(key, value);
        }
        return memStat;
    }

    public HashMap<String, HashMap<String, Long>> getCpuMetrics(){
        HashMap<String, HashMap<String, Long>> cpuMetrics = new HashMap<>();
        for (Map.Entry<String, String> entry : contaienrIdMap.entrySet()){
            if(entry.getKey() == "000001")
                continue;
            String fileName = cgroupCPUDir + entry.getValue() + "/" + cpuStat;
            HashMap<String, Long> stat = getStat(fileName);
            if(stat == null)
                continue;
            cpuMetrics.put(entry.getKey(), stat);
        }
        return cpuMetrics;

    }


    public String getCGroupParam(String cGroupParamPath){
        try {
            byte[] contents = Files.readAllBytes(Paths.get(cGroupParamPath));
            String s = new String(contents, "UTF-8");
//            LOG.info("cgroup tell us: " + s);
            return new String(contents, "UTF-8").trim();
        } catch (IOException e) {
            LOG.info("Unable to read from " + cGroupParamPath);
            return null;
        }
    }

    public void setContaienrIdMap(Map<String, String> contaienrIdMap) {
        this.contaienrIdMap = contaienrIdMap;
    }

}
