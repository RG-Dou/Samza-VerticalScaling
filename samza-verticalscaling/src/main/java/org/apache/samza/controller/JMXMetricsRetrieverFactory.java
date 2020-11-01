package org.apache.samza.controller;

import org.apache.samza.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXMetricsRetrieverFactory implements StreamSwitchMetricsRetrieverFactory{
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.JMXMetricsRetrieverFactory.class);
    @Override
    public StreamSwitchMetricsRetriever getRetriever(Config config) {
        return new JMXMetricsRetriever(config);
    }
}