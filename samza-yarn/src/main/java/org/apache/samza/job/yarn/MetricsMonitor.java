package org.apache.samza.job.yarn;

import org.apache.samza.SamzaException;
import org.apache.samza.config.JobConfig;
import org.apache.samza.container.SamzaContainerMetrics;
import org.apache.samza.coordinator.JobModelManager;
import org.apache.samza.job.model.ContainerModel;
import org.apache.samza.job.model.JobModel;
import org.apache.samza.metrics.JmxMetricsAccessor;
import org.apache.samza.metrics.MetricsAccessor;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.rmi.runtime.Log;

public class MetricsMonitor {

    private static final Logger log = LoggerFactory.getLogger(MetricsMonitor.class);

    private final JobConfig config;
    private final YarnClient client;
    private final String jobName;
    private final JobModelManager jobModelManager;

    public MetricsMonitor(JobConfig config, YarnClient client, JobModelManager jobModelManager) {
        this.config = config;
        this.client = client;
        String name = this.config.getName().get();
        String jobId = this.config.getJobId();
        this.jobName =  name + "_" + jobId;
        this.jobModelManager = jobModelManager;
    }

    public void run() {
        ApplicationId appId;
        ApplicationAttemptId attemptId;

        try {
//            log.info("Start validating job " + this.jobName);

            appId = validateAppId();
            attemptId = validateRunningAttemptId(appId);
//            validateContainerCount(attemptId);

            validateJmxMetrics(attemptId, jobModelManager.jobModel());

//            log.info("End of validation");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            System.exit(1);
        }
    }

    public ApplicationId validateAppId() throws Exception {
        // fetch only the last created application with the job name and id
        // i.e. get the application with max appId
        ApplicationId appId = null;
        for(ApplicationReport applicationReport : this.client.getApplications()) {
            if(applicationReport.getName().equals(this.jobName)) {
                ApplicationId id = applicationReport.getApplicationId();
                if(appId == null || appId.compareTo(id) < 0) {
                    appId = id;
                }
            }
        }
        if (appId != null) {
//            log.info("Job lookup success. ApplicationId " + appId.toString());
            return appId;
        } else {
            throw new SamzaException("Job lookup failure " + this.jobName);
        }
    }

    public ApplicationAttemptId validateRunningAttemptId(ApplicationId appId) throws Exception {
        ApplicationAttemptId attemptId = this.client.getApplicationReport(appId).getCurrentApplicationAttemptId();
        ApplicationAttemptReport attemptReport = this.client.getApplicationAttemptReport(attemptId);
        if (attemptReport.getYarnApplicationAttemptState() == YarnApplicationAttemptState.RUNNING) {
//            log.info("Job is running. AttempId " + attemptId.toString());
            return attemptId;
        } else {
            throw new SamzaException("Job not running " + this.jobName);
        }
    }

    public int validateContainerCount(ApplicationAttemptId attemptId) throws Exception {
        int runningContainerCount = 0;
        for(ContainerReport containerReport : this.client.getContainers(attemptId)) {
            if(containerReport.getContainerState() == ContainerState.RUNNING) {
                ++runningContainerCount;
            }
        }
        // expected containers to be the configured job containers plus the AppMaster container
        int containerExpected = this.config.getContainerCount() + 1;

        if (runningContainerCount == containerExpected) {
            log.info("Container count matches. " + runningContainerCount + " containers are running.");
            return runningContainerCount;
        } else {
            throw new SamzaException("Container count does not match. " + runningContainerCount + " containers are running, while " + containerExpected + " is expected.");
        }
    }

    public void validateJmxMetrics(ApplicationAttemptId attemptId, JobModel jobModel) throws Exception {
        Map<String, String> jmxUrls = new UrlExtract().getJmxUrls(attemptId, this.client);
//        log.info("jmxUris is " + jmxUrls.toString());
        MetricsConfig config = new MetricsConfig(jobModel);
        for (Map.Entry<String, String> entry : jmxUrls.entrySet()) {
            String containerId = entry.getKey();
            String jmxUrl = entry.getValue();
            log.info("validate container " + containerId + " metrics with JMX: " + jmxUrl);
            JmxMetricsAccessor jmxMetrics = new JmxMetricsAccessor(jmxUrl);
            jmxMetrics.connect();
            validate(jmxMetrics, config);
            jmxMetrics.close();
//            log.info("validate container " + containerId + " successfully");
        }
    }

//    public void validate(MetricsAccessor accessor) {
////        Map<String, Long> commitCalls = accessor.getCounterValues(SamzaContainerMetrics.class.getName(), "commit-calls");
////        if(commitCalls.isEmpty()) log.info("no commit-calls");
////        for(Map.Entry<String, Long> entry: commitCalls.entrySet()) {
////            log.info("commit call " + entry.getKey() + " is " + entry.getValue());
////        }
////    }
    public void validate(MetricsAccessor accessor, MetricsConfig config){
        ArrayList<String> groupsName = config.getGroup();
        HashMap<String, String> metrics = new HashMap<String, String>();
        for(String group : groupsName) {
            for (String counterName : config.getCounter(group)) {
                Map<String, Long> counter = accessor.getCounterValues(group, counterName);
                for (Map.Entry<String, Long> entry : counter.entrySet()) {
                    metrics.put(counterName, entry.getValue().toString());
                    break;
                }
            }
            for (String counterName : config.getTimer(group)) {
                Map<String, Double> timer = accessor.getTimerValues(group, counterName);
                for (Map.Entry<String, Double> entry : timer.entrySet()) {
                    metrics.put(counterName, entry.getValue().toString());
                    break;
                }
            }
            for (String counterName : config.getGaugeDouble(group)) {
                try {
                    Map<String, Double> gauge = accessor.getGaugeValues(group, counterName);
                    for (Map.Entry<String, Double> entry : gauge.entrySet()) {
                        metrics.put(counterName, entry.getValue().toString());
                        break;
                    }
                }catch (Exception e){
                    log.info("metrics name" + counterName);
                    log.error("metrics name" + counterName);
                }
            }
            for (String counterName : config.getGaugeLong(group)) {
                Map<String, Long> gauge = accessor.getGaugeValues(group, counterName);
                for (Map.Entry<String, Long> entry : gauge.entrySet()) {
                    metrics.put(counterName, entry.getValue().toString());
                    break;
                }
            }
            for (String counterName : config.getGaugeInt(group)) {
                Map<String, Integer> gauge = accessor.getGaugeValues(group, counterName);
                for (Map.Entry<String, Integer> entry : gauge.entrySet()) {
                    metrics.put(counterName, entry.getValue().toString());
                    break;
                }
            }
            for (String counterName : config.getGaugeFloat(group)) {
                Map<String, Float> gauge = accessor.getGaugeValues(group, counterName);
                for (Map.Entry<String, Float> entry : gauge.entrySet()) {
                    metrics.put(counterName, entry.getValue().toString());
                    break;
                }
            }

//            log.info("For group: " + group + " Metrics are: " + metrics.toString());
        }
        log.info("Metrics are: " + metrics.toString());
    }


    private class UrlExtract {

        public Map<String, String> getJmxUrls(ApplicationAttemptId attemptId, YarnClient client) throws Exception {
//            String url = "http://alligator-sane:8042/node/containerlogs/container_1585904894600_0001_01_000002/drg/samza-container-0-startup.log";
            Map<String, String> jmxUrls = new HashMap<String, String>();
//            log.info("Start extract JMX url for attemptId " + attemptId.toString());
            for (ContainerReport containerReport : client.getContainers(attemptId)) {
                if (containerReport.getContainerState() == ContainerState.RUNNING) {
                    String containerId = containerReport.getContainerId().toString();
                    String logUrl = containerReport.getLogUrl();
//                    log.info("Start extract JMX url for containerId " + containerId + " and log url is " + logUrl);
                    String jmxUrl = getJmxUrl(logUrl);
                    if (jmxUrl != null) {
                        jmxUrls.put(containerId, jmxUrl);
                    }
                }
            }
            return jmxUrls;
        }

        public String getJmxUrl(String logUrl) {
            String html = "";
            try {
                html = getOneHtml(logUrl);

                Pattern pa = Pattern.compile("/samza.[\\s\\S]*?startup.log", Pattern.CANON_EQ);
                Matcher ma = pa.matcher(html);
                String startupUrl = null;
                while (ma.find()) {
                    startupUrl = ma.group();
                }

                if (startupUrl == null) {
//                    log.info("no start up url and log dir context is " + html.substring(0, 10));
                    return null;
                }

//                log.info("startup url is " + startupUrl);

                String startupHtml = "";
                startupHtml = getOneHtml(logUrl + startupUrl);
                String startupContext = getHtmlContext(startupHtml, "<pre>.*?</pre>");
                if (startupContext == null) {
//                    log.info("no start up context");
                    return null;
                }
                String jmxUrl = getUrlFromHtml(startupContext);
                if (jmxUrl == null) {
//                    log.info("no jmx url and start up context is " + startupContext);
                    return null;
                }
                return jmxUrl;

            } catch (final Exception e) {
                log.error(e.getMessage());
            }
            return null;
        }

        public String getOneHtml(final String htmlUrl) throws IOException {
            URL url;
            String temp;
            final StringBuffer sb = new StringBuffer();
            try {
                url = new URL(htmlUrl);
                final BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));// 读取网页全部内容
                while ((temp = in.readLine()) != null) {
                    sb.append(temp);
                }
                in.close();
            } catch (final MalformedURLException me) {
                log.error("Wrong html url!");
                me.getMessage();
                throw me;
            } catch (final IOException e) {
                e.printStackTrace();
                throw e;
            }
            return sb.toString();
        }

        public String getHtmlContext(String s, String regex) {
            String logs = "";
            final List<String> list = new ArrayList<String>();
            final Pattern pa = Pattern.compile(regex, Pattern.CANON_EQ);
            final Matcher ma = pa.matcher(s);
            while (ma.find()) {
                list.add(ma.group());
            }
            for (int i = 0; i < list.size(); i++) {
                logs = logs + list.get(i);
            }

            return outTag(logs);
        }

        public String getUrlFromHtml(String context) {
            String url = null;
            String[] logArr = context.split("STARTUP_LOGGER");
            for (int i = 0; i < logArr.length; i++) {
                if (logArr[i].contains("If you are tunneling, you might want to try JmxServer registry")) {
                    int index = logArr[i].indexOf("service:jmx:rmi:");
                    url = logArr[i].substring(index, logArr[i].length());
                }
            }
            return url;
        }

        public String outTag(final String s) {
            return s.replaceAll("<.*?>", "");
        }
    }
}