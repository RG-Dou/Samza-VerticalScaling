package org.apache.samza.job.yarn;

import org.apache.samza.container.SamzaContainer;
import org.apache.samza.container.SamzaContainerMetrics;
import org.apache.samza.container.TaskInstanceMetrics;
import org.apache.samza.container.TaskName;
import org.apache.samza.job.model.ContainerModel;
import org.apache.samza.job.model.JobModel;
import org.apache.samza.job.model.TaskModel;
import org.apache.samza.metrics.JvmMetrics;
import org.apache.samza.system.SystemConsumersMetrics;
import org.apache.samza.system.SystemStreamPartition;

import java.util.*;

public class MetricsConfig {

    private static String samzaContainer = SamzaContainerMetrics.class.getName();
    private static ArrayList<String> samzaContainerCounter =
            new ArrayList<String>(Arrays.asList(
//                    "process-calls",
//                    "process-envelopes"
//                    "process-null-envelopes"
            ));
    private static ArrayList<String> samzaContainerGaugeDouble =
            new ArrayList<String>(Arrays.asList(
//                    "event-loop-utilization",
                    "physical-memory-mb"
            ));

    private static String taskInstance = TaskInstanceMetrics.class.getName();
    private static ArrayList<String> taskInstanceCounter =
            new ArrayList<String>(Arrays.asList(
//                    "process-calls",
//                    "send-calls",
                    "messages-actually-processed"
//                    "async-callback-complete-calls"
            ));
//    private static ArrayList<String> taskInstanceGaugeInt =
//            new ArrayList<String>(Arrays.asList(
//                    "pending-messages",
//                    "messages-in-flight"
//            ));

    private static String jvm = JvmMetrics.class.getName();
    private static ArrayList<String> jvmGaugeFloat =
            new ArrayList<String>(Arrays.asList(
                    "mem-non-heap-used-mb",
                    "mem-non-heap-committed-mb",
//                    "mem-non-heap-max-mb",
                    "mem-heap-used-mb",
                    "mem-heap-committed-mb"
//                    "mem-heap-max-mb"
            ));
//    private static ArrayList<String> jvmGaugeDouble =
//            new ArrayList<String>(Arrays.asList(
//                    "process-cpu-usage",
//                    "system-cpu-usage"
//            ));
//    private static ArrayList<String> jvmGaugeLong =
//            new ArrayList<String>(Arrays.asList(
//                    "threads-runnable",
//                    "threads-blocked",
//                    "threads-waiting"
//            ));

    private static String systemConsumer = SystemConsumersMetrics.class.getName();
    private static ArrayList<String> systemConsumerGaugeInt =
            new ArrayList<String>(Arrays.asList(
                    "unprocessed-messages"
            ));

    private static String kafkaSystemConsumer = "org.apache.samza.system.kafka.KafkaSystemConsumerMetrics";


    private ArrayList<String> groupsName = new ArrayList<String>();
    private HashMap<String, HashMap<String, ArrayList<String>>> groups = new HashMap<String, HashMap<String, ArrayList<String>>>();

    public MetricsConfig(JobModel jobModel){
        HashMap<String, ArrayList<String>> samzaContainerMetrics = new HashMap<String, ArrayList<String>>();
//        samzaContainerMetrics.put("Counter", samzaContainerCounter);
        samzaContainerMetrics.put("GaugeDouble", samzaContainerGaugeDouble);
        groups.put(samzaContainer, samzaContainerMetrics);
        groupsName.add(samzaContainer);

        HashMap<String, ArrayList<String>> taskInstanceMetrics = new HashMap<String, ArrayList<String>>();
        taskInstanceMetrics.put("Counter", taskInstanceCounter);
//        taskInstanceMetrics.put("GaugeInt", taskInstanceGaugeInt);
        groups.put(taskInstance, taskInstanceMetrics);
        groupsName.add(taskInstance);

        HashMap<String, ArrayList<String>> jvmMetrics = new HashMap<String, ArrayList<String>>();
        jvmMetrics.put("GaugeFloat", jvmGaugeFloat);
//        jvmMetrics.put("GaugeDouble", jvmGaugeDouble);
//        jvmMetrics.put("GaugeLong", jvmGaugeLong);
        groups.put(jvm, jvmMetrics);
        groupsName.add(jvm);

        HashMap<String, ArrayList<String>> systemConsumerMetrics = new HashMap<String, ArrayList<String>>();
        systemConsumerMetrics.put("GaugeInt", systemConsumerGaugeInt);
        groups.put(systemConsumer, systemConsumerMetrics);
        groupsName.add(systemConsumer);

        ArrayList<String> kafkaSystemCount = new ArrayList<String>();
        ArrayList<String> kafkaSystemGaugeLong = new ArrayList<String>();

        Map<String, ContainerModel> containers = jobModel.getContainers();
        for (Map.Entry<String, ContainerModel> container : containers.entrySet()) {
            ContainerModel containerModel = container.getValue();
            Map<TaskName, TaskModel> tasks = containerModel.getTasks();
            for (Map.Entry<TaskName, TaskModel> entry : tasks.entrySet()) {
                TaskModel model = entry.getValue();
                Set<SystemStreamPartition> ssps = model.getSystemStreamPartitions();
                for (SystemStreamPartition ssp : ssps) {
                    String sspStr = ssp.getSystem() + "-" + ssp.getStream() + "-" + ssp.getPartition().getPartitionId();
                    String metrics = sspStr + "-offset-change";
//                    kafkaSystemCount.add(metrics);
//                    metrics = sspStr + "-high-watermark";
//                    kafkaSystemGaugeLong.add(metrics);
                    metrics = sspStr + "-messages-behind-high-watermark";
                    kafkaSystemGaugeLong.add(metrics);
                }
            }
        }
        HashMap<String, ArrayList<String>> kafkaSystemConsumerMetrics = new HashMap<String, ArrayList<String>>();
//        kafkaSystemConsumerMetrics.put("Counter", kafkaSystemCount);
        kafkaSystemConsumerMetrics.put("GaugeLong", kafkaSystemGaugeLong);
        groups.put(kafkaSystemConsumer, kafkaSystemConsumerMetrics);
        groupsName.add(kafkaSystemConsumer);

    }

    public ArrayList<String> getGroup(){
        return groupsName;
    }

    public ArrayList<String> getCounter(String group){
        return groups.get(group).get("Counter") == null? new ArrayList<String>() : groups.get(group).get("Counter");
    }

    public ArrayList<String> getTimer(String group){
        return groups.get(group).get("Timer") == null? new ArrayList<String>() : groups.get(group).get("Timer");
    }

    public ArrayList<String> getGaugeDouble(String group){
        return groups.get(group).get("GaugeDouble") == null? new ArrayList<String>() : groups.get(group).get("GaugeDouble");
    }

    public ArrayList<String> getGaugeLong(String group){
        return groups.get(group).get("GaugeLong") == null? new ArrayList<String>() : groups.get(group).get("GaugeLong");
    }

    public ArrayList<String> getGaugeFloat(String group){
        return groups.get(group).get("GaugeFloat") == null? new ArrayList<String>() : groups.get(group).get("GaugeFloat");
    }

    public ArrayList<String> getGaugeInt(String group){
        return groups.get(group).get("GaugeInt") == null? new ArrayList<String>() : groups.get(group).get("GaugeInt");
    }
}
