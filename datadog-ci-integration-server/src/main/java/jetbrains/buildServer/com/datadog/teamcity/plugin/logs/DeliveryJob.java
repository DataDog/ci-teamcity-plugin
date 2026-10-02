/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

public class DeliveryJob {
    public long buildId;
    public String jobId;
    public String webhookPayload;

    public DeliveryJob() {
    }

    public DeliveryJob(long buildId, String jobId, String webhookPayload) {
        this.buildId = buildId;
        this.jobId = jobId;
        this.webhookPayload = webhookPayload;
    }
}
