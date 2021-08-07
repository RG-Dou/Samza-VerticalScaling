package org.apache.samza.controller;

import org.apache.samza.config.Config;
import org.apache.samza.controller.OperatorController;
import org.apache.samza.controller.OperatorControllerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimplePolicyFactory implements OperatorControllerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(SimplePolicyFactory.class);

    public SimplePolicy getController(Config config) {
        return new SimplePolicy(config);
    }
}
