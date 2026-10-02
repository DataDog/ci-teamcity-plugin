/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

public class LogDeliveryException extends RuntimeException {
    private final DeliveryResult result;

    public LogDeliveryException(DeliveryResult result, String message) {
        super(message);
        this.result = result;
    }

    public DeliveryResult getResult() {
        return result;
    }
}
