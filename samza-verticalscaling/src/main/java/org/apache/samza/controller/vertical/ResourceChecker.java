package org.apache.samza.controller.vertical;

import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.samza.controller.OperatorControllerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            Resource currentResource = allMetrics.get(containerId);
            if (target.equals(currentResource)) {
                containers.remove(containerId);
                LOG.info("Container " + containerId + " adjusted successfully. Target resource " + target.toString());
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
