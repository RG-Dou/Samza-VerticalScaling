package org.apache.samza.job.yarn;

import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.security.ContainerTokenIdentifier;
import org.apache.hadoop.io.Text;
import org.apache.samza.clustermanager.SamzaResource;
import org.apache.samza.config.Config;
import org.apache.samza.config.JobConfig;
import org.apache.samza.coordinator.JobModelManager;
import org.apache.samza.util.hadoop.HttpFileSystem;
import static java.lang.Thread.sleep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//Created By DrG
public class VerticalAutoScaling implements Runnable  {


    /**
     * The containerProcessManager instance to request resources from yarn.
     */
    private final AMRMClientAsync<AMRMClient.ContainerRequest> amClient;
    private final ConcurrentHashMap<SamzaResource, Container> allocatedResources;
    private final JobConfig config;
    private final JobModelManager jobModelManager;
    private final NMClientAsync nmClientAsync;


    private static final Logger log = LoggerFactory.getLogger(VerticalAutoScaling.class);

    public VerticalAutoScaling(Config config, AMRMClientAsync<AMRMClient.ContainerRequest> amClient,
                               ConcurrentHashMap<SamzaResource, Container> allocatedResources,
                               NMClientAsync nmClientAsync){
        this(config, amClient, allocatedResources, null, nmClientAsync);
    }

    public VerticalAutoScaling(Config config, AMRMClientAsync<AMRMClient.ContainerRequest> amClient,
                               ConcurrentHashMap<SamzaResource, Container> allocatedResources, JobModelManager jobModelManager,
                               NMClientAsync nmClientAsync){
        this.config = new JobConfig(config);
        this.amClient = amClient;
        this.allocatedResources = allocatedResources;
        this.jobModelManager = jobModelManager;
        this.nmClientAsync = nmClientAsync;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(60*1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        YarnConfiguration hadoopConfig = new YarnConfiguration();
        hadoopConfig.set("fs.http.impl", HttpFileSystem.class.getName());
        hadoopConfig.set("fs.https.impl", HttpFileSystem.class.getName());
        ClientHelper clientHelper = new ClientHelper(hadoopConfig);
        MetricsMonitor metricsMonitor = new MetricsMonitor(this.config, clientHelper.yarnClient(), jobModelManager);

        for (Map.Entry<SamzaResource, Container> entry: allocatedResources.entrySet()) {
            Container container = entry.getValue();
            ContainerId containerId = container.getId();
            List increaseRequests = Arrays.asList(
                    ContainerResourceChangeRequest.newInstance(containerId, Resources.createResource(4 * 1024, 2)));
            try {
                AllocateResponse response = amClient.increaseContainersSize(increaseRequests, null);

                List<Container> increasedContainers = response.getIncreasedContainers();
                for(Container increasedContainer : increasedContainers){
                    increaseContainersNM(increasedContainer);
                }



            } catch (IOException e){
                log.error("Error in increasing the container size:", e);
            } catch (YarnException e){
                log.error("Error in increasing the container size:", e);
            }
        }

        while (true) {
            metricsMonitor.run();
            try {
                sleep(1000*60);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void increaseContainersNM(Container container){
        log.info("Received increased container from RM. Container ID: " + container.getId().toString() + ". Target resource is: " + container.getResource().toString());
        nmClientAsync.increaseContainerResourceAsync(container);
    }

}
