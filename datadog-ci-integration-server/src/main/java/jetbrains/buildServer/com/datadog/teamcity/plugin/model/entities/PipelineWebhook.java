/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import java.util.Objects;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook.CILevel.PIPELINE;

public class PipelineWebhook extends Webhook {

    @JsonProperty("unique_id")
    @Nonnull
    private final String uniqueId;

    @JsonProperty("pipeline_id")
    @Nonnull
    private final String pipelineId;

    @JsonProperty("partial_retry")
    private final boolean partialRetry;

    @JsonProperty("status")
    @Nonnull
    private final PipelineStatus status;

    public PipelineWebhook(@Nonnull String name,
                           @Nonnull String url,
                           @Nonnull String start,
                           @Nonnull String end,
                           @Nonnull String uniqueId,
                           @Nonnull String pipelineId,
                           boolean partialRetry,
                           @Nonnull PipelineStatus status) {
        super(PIPELINE, name, url, start, end);
        this.uniqueId = uniqueId;
        this.pipelineId = pipelineId;
        this.partialRetry = partialRetry;
        this.status = status;
    }

    @Override
    public String toString() {
        return "PipelineWebhook{" +
            "uniqueId='" + uniqueId + '\'' +
            ", pipelineId='" + pipelineId + '\'' +
            ", partialRetry=" + partialRetry +
            ", status=" + status +
            ", level=" + level +
            ", name='" + name + '\'' +
            ", url='" + url + '\'' +
            ", start='" + start + '\'' +
            ", end='" + end + '\'' +
            ", gitInfo=" + gitInfo +
            "} " + super.toString();
    }

    @Nonnull
    public String name() { return name; }

    @Nonnull
    public PipelineStatus status() {
        return status;
    }

    @Override
    public String id() {
        return uniqueId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        PipelineWebhook that = (PipelineWebhook) o;
        return partialRetry == that.partialRetry && uniqueId.equals(that.uniqueId) && pipelineId.equals(that.pipelineId) && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), uniqueId, pipelineId, partialRetry, status);
    }

    public enum PipelineStatus {
        @JsonProperty("success") SUCCESS,
        @JsonProperty("error") ERROR,
        @JsonProperty("canceled") CANCELED
    }

}
