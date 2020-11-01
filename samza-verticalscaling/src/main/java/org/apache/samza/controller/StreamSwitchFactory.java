package org.apache.samza.controller;

import org.apache.samza.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class StreamSwitchFactory implements OperatorControllerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(org.apache.samza.controller.StreamSwitchFactory.class);
    public abstract StreamSwitch getController(Config config);
}

