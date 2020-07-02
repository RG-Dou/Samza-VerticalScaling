package org.apache.samza.controller;

import java.util.List;

public interface OperatorController {
    void init(OperatorControllerListener listener, List<String> partitions, List<String> executors);
    void start();
    //Methods used to inform Controller
    void onMigrationExecutorsStopped();
    void onMigrationCompleted();

    //DrG
    void onResourceResized(String processorId, String resourceModel);
    void onContainerResized(String processorId, String result);
}
