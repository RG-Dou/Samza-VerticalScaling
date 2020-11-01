package org.apache.samza.controller;

import org.apache.samza.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyGuarantorFactory extends StreamSwitchFactory {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.LatencyGuarantorFactory.class);

    @Override
    public StreamSwitch getController(Config config) {
        return new LatencyGuarantor(config);
    }
}
