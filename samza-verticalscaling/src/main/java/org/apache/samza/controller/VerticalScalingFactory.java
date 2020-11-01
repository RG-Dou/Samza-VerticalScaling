package org.apache.samza.controller;

import org.apache.samza.config.Config;
import org.apache.samza.controller.OperatorController;
import org.apache.samza.controller.OperatorControllerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VerticalScalingFactory implements OperatorControllerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(VerticalScalingFactory.class);

    public VerticalScaling getController(Config config) {
        return new VerticalScaling(config);
    }
}
