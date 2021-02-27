package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.controller.OperatorControllerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ResourceChecker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.vertical.ResourceChecker.class);
    private final YarnContainerMetrics clientMetrics;
    private final ConcurrentMap<String, Resource> shrinksTargets =new ConcurrentHashMap<String, Resource>();
    private final ConcurrentMap<String, Resource> expandsTargets =new ConcurrentHashMap<String, Resource>();
    private OperatorControllerListener listener;
    private final long waitingLimit = 5000L;

    private final String cgroupDir = "/sys/fs/cgroup/memory/yarn/";
    private final String memConfig = "memory.limit_in_bytes";
    private final String memUsed = "memory.usage_in_bytes";
    private final int retries = 10000;

    public enum status {
        SLEEPING,
        SHRINKING,
        EXPANDING;
    }

    private volatile status runningStatus = status.SLEEPING;
    private boolean isRunning = true;
    private long checkStart = System.currentTimeMillis();

    public ResourceChecker(String jobName){
        clientMetrics = new YarnContainerMetrics(jobName);
    }

    @Override
    public void run() {
        while(isRunning){
            if (runningStatus == status.SHRINKING){
                if (shrinksTargets.size() == 0){
                    LOG.info("Resource shrinking is successfully.");
                    LOG.info("Start expand now: " + expandsTargets);
                    resize(expandsTargets);
                    runningStatus = status.EXPANDING;
                } else {
                    monitor(shrinksTargets);
                }
            } else if(runningStatus == status.EXPANDING){
                if (expandsTargets.size() == 0){
                    runningStatus = status.SLEEPING;
                    LOG.info("Resource expanding is successfully.");
                } else {
                    monitor(expandsTargets);
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void resize(Map<String, Resource> containers){
        for (Map.Entry<String, Resource> entry : containers.entrySet()){
//            LOG.info("ID" + entry.getKey() + ", cpu: " + entry.getValue().getVirtualCores() + ", mem: " + entry.getValue().getMemory());
            listener.containerResize(entry.getKey(), entry.getValue().getVirtualCores(), entry.getValue().getMemory());
        }
    }

    private void monitor(Map<String, Resource> containers){
        Map<String, Resource> allMetrics = clientMetrics.getAllMetrics();
        for (Map.Entry<String, Resource> entry : containers.entrySet()) {
            String containerId = entry.getKey();
            Resource target = entry.getValue();
            if (!allMetrics.containsKey(containerId))
                continue;
            Resource currentResource = allMetrics.get(containerId);

            if (target.equals(currentResource)) {
                if(runningStatus == status.SHRINKING) {
                    checkAndAdjust(containerId, currentResource.getMemory());
                }
                containers.remove(containerId);
                LOG.info("Container " + containerId + " adjusted successfully. Target resource " + target.toString());
            }
        }
    }

    private void checkAndAdjust(String containerId, int targetMem){
        String containerIdFull = clientMetrics.getFullContainerId(containerId);
        String configFile = cgroupDir + containerIdFull + "/" + memConfig;
        if(!checkMemConsistency(configFile, targetMem)){
            LOG.info("Force change the memory config.");
            for (int i = 0; i < retries; i++){
                try{
                    updateCGroupParam(configFile, Integer.toString(targetMem * 1024 * 1024));
                    LOG.info("Force change the memory config successfully.");
                    break;
                } catch (IOException e){
                    if(checkMemConsistency(configFile, targetMem)){
                        LOG.info("Force change the memory config successfully.");
                        break;
                    } else {
                        continue;
                    }
                }
            }
        }
        if(!checkMemConsistency(configFile, targetMem)){
            LOG.info("Force change the memory config unsuccessfully.");
        }
    }

    private boolean checkMemConsistency(String fileName, int targetMem){
        Long cgroupMem = Long.parseLong(getCGroupParam(fileName)) / 1024 / 1024;
        if(cgroupMem == targetMem) {
            LOG.info("No need to adjust memory");
            return true;
        } else {
            LOG.info("We have to adjust memory");
            return false;
        }
    }


    public String getCGroupParam(String cGroupParamPath){
        try {
            byte[] contents = Files.readAllBytes(Paths.get(cGroupParamPath));
            String s = new String(contents, "UTF-8");
            LOG.info("cgroup tell us: " + s);
            return new String(contents, "UTF-8").trim();
        } catch (IOException e) {
            LOG.info("Unable to read from " + cGroupParamPath);
            return null;
        }
    }


    public void updateCGroupParam(String cGroupParamPath, String value) throws IOException {
        PrintWriter pw = null;

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "updateCGroupParam for path: " + cGroupParamPath + " with value " +
                            value);
        }

        try {
            File file = new File(cGroupParamPath);
            Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            pw = new PrintWriter(w);
            pw.write(value);
        } catch (IOException e) {
            LOG.info(new StringBuffer("Unable to write to ")
                    .append(cGroupParamPath).append(" with value: ").append(value)
                    .toString());
            throw e;
        } finally {
            if (pw != null) {
                boolean hasError = pw.checkError();
                pw.close();
                if (hasError) {
                    LOG.info(new StringBuffer("Unable to write to ")
                            .append(cGroupParamPath).append(" with value: ").append(value)
                            .toString());
                }
                if (pw.checkError()) {
                    LOG.info(new StringBuffer("Error while closing cgroup file" +
                            " " + cGroupParamPath).toString());
                }
            }
        }
    }


    public boolean startAdjust(Map<String, Resource> shrinks, Map<String, Resource> expands){
        if (runningStatus == status.SLEEPING) {
            shrinksTargets.putAll(shrinks);
            expandsTargets.putAll(expands);

            LOG.info("Start shrink now: ");
            resize(shrinksTargets);
            runningStatus = status.SHRINKING;
            checkStart = System.currentTimeMillis();
            return true;
        } else{
            return false;
        }
    }

    public void stop() {
        runningStatus = status.SLEEPING;
    }

    public boolean isSleeping() {
        return runningStatus == status.SLEEPING;
    }

    public void setListener(OperatorControllerListener listener){
        this.listener = listener;
    }
}
