package org.apache.samza.controller;

import org.apache.commons.collections.map.HashedMap;
import org.apache.samza.config.Config;
import org.apache.samza.controller.OperatorController;
import org.apache.samza.controller.OperatorControllerListener;
import org.apache.samza.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class VerticalScaling extends StreamSwitch{
    private static final Logger LOG = LoggerFactory.getLogger(VerticalScaling.class);
    protected OperatorControllerListener listener;
    ReentrantLock updateLock; //Lock is used to avoid concurrent modify between update() and changeImplemented()
    Config config;

    public VerticalScaling(Config config){
        super(config);
        this.config = config;
        updateLock = new ReentrantLock();
    }
    @Override
    public void init(OperatorControllerListener listener, List<String> executors, List<String> substreams){
        super.init(listener, executors, substreams);
        this.listener = listener;
    }
//
//    @Override
//    public void start(){
//        try {
//            Thread.sleep(50*1000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        HashMap<String, Long> resourceMap = new HashMap<String, Long>();
//        resourceMap.put("000002", 1024L);
//        resourceMap.put("000003", 1024L);
//        LOG.info("resourceMap: " + resourceMap.toString());
//        listener.storeMemResize(resourceMap);
//    }

    @Override
    void work(long timeIndex) {
        Map<String, Object> metrics = metricsRetriever.retrieveMetrics();
    }

    @Override
    public void onMigrationExecutorsStopped() {

    }

    @Override
    public void onMigrationCompleted() {

    }

    @Override
    public void onResourceResized(String processorId, String extendedJobModel){
        listener.containerResize(processorId, 2, 4096);
    }

    @Override
    public void onContainerResized(String processorId, String result){

    }
}