/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.model.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Official Datadog CI Visibility Pipelines API request wrapper.
 * See: https://docs.datadoghq.com/api/latest/ci-visibility-pipelines/#send-pipeline-event
 * 
 * This file contains all the nested wrapper classes for the API request structure:
 * - CIAppPipelineEventRequest (top level)
 * - Data (array of event data)
 * - Attributes (env, provider_name, service, resource)
 */
public class CIAppPipelineEventRequest {

    @JsonProperty("data")
    @Nonnull
    private final List<Data> data;

    public CIAppPipelineEventRequest(@Nonnull List<Data> data) {
        this.data = data;
    }

    /**
     * Factory method to create request from webhooks.
     */
    public static CIAppPipelineEventRequest fromWebhooks(
            @Nonnull List<Webhook> webhooks,
            @Nonnull String providerName,
            @Nullable String service,
            @Nullable String env) {
        List<Data> dataList = webhooks.stream()
            .map(webhook -> new Data(new Attributes(env, providerName, service, webhook)))
            .collect(Collectors.toList());
        return new CIAppPipelineEventRequest(dataList);
    }

    /**
     * Data wrapper for CI pipeline events.
     */
    public static class Data {
        @JsonProperty("attributes")
        @Nonnull
        private final Attributes attributes;

        @JsonProperty("type")
        @Nonnull
        private final String type;

        public Data(@Nonnull Attributes attributes) {
            this.attributes = attributes;
            this.type = "cipipeline_resource_request";
        }
    }

    /**
     * Attributes for CI pipeline events.
     * Contains environment, provider name, service identifier, and the actual pipeline/job/step resource.
     */
    public static class Attributes {
        @JsonProperty("env")
        @Nullable
        private final String env;

        @JsonProperty("provider_name")
        @Nonnull
        private final String providerName;

        @JsonProperty("service")
        @Nullable
        private final String service;

        @JsonProperty("resource")
        @Nonnull
        private final Webhook resource;

        public Attributes(
                @Nullable String env,
                @Nonnull String providerName,
                @Nullable String service,
                @Nonnull Webhook resource) {
            this.env = env;
            this.providerName = providerName;
            this.service = service;
            this.resource = resource;
        }
    }
}
