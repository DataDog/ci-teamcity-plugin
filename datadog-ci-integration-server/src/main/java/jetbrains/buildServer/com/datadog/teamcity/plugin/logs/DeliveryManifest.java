/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import java.util.ArrayList;
import java.util.List;

public class DeliveryManifest {
    public long pipelineBuildId;
    public long createdAtMillis;
    public String pipelineId;
    public String ddSite;
    public String pipelineWebhookPayload;
    public List<DeliveryJob> jobs = new ArrayList<>();

    public DeliveryManifest() {
    }

    public DeliveryManifest(long pipelineBuildId, String pipelineId, String ddSite,
                            String pipelineWebhookPayload, List<DeliveryJob> jobs) {
        this.pipelineBuildId = pipelineBuildId;
        this.createdAtMillis = System.currentTimeMillis();
        this.pipelineId = pipelineId;
        this.ddSite = ddSite;
        this.pipelineWebhookPayload = pipelineWebhookPayload;
        this.jobs = jobs;
    }
}
