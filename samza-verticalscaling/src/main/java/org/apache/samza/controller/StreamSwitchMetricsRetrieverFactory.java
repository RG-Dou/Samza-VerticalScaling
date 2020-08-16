package org.apache.samza.controller;

import org.apache.samza.config.Config;

interface StreamSwitchMetricsRetrieverFactory {
    StreamSwitchMetricsRetriever getRetriever(Config config);
}
