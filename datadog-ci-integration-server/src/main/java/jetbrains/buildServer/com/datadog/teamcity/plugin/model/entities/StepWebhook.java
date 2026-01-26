/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook.CILevel.STEP;

/**
 * Represents a step-level event in the Datadog CI Visibility data model.
 * Steps are individual build tasks (e.g., checkout, compile, test) that run within a job.
 * 
 * <p>This webhook extends the base Webhook class and adds references to the parent job and pipeline,
 * following the hierarchical structure: Pipeline → Job → Step.</p>
 * 
 * <p><b>Note:</b> Due to TeamCity API limitations, the status field is currently always set to SUCCESS.
 * TeamCity's public Java API does not expose individual step success/failure status.</p>
 */
public class StepWebhook extends Webhook {

    @JsonProperty("job_id")
    @Nonnull
    private final String jobId;

    @JsonProperty("job_name")
    @Nonnull
    private final String jobName;

    @JsonProperty("pipeline_unique_id")
    @Nonnull
    private final String pipelineUniqueId;

    @JsonProperty("pipeline_name")
    @Nonnull
    private final String pipelineName;

    @JsonProperty("id")
    @Nonnull
    private final String id;

    @JsonProperty("status")
    @Nonnull
    private final StepStatus status;

    @JsonProperty("error")
    @Nullable
    private final JobWebhook.ErrorInfo errorInfo;

    public StepWebhook(@Nonnull String name,
                       @Nonnull String url,
                       @Nonnull String start,
                       @Nonnull String end,
                       @Nonnull String jobId,
                       @Nonnull String jobName,
                       @Nonnull String pipelineUniqueId,
                       @Nonnull String pipelineName,
                       @Nonnull String id,
                       @Nonnull StepStatus status,
                       @Nullable JobWebhook.ErrorInfo errorInfo) {
        super(STEP, name, url, start, end);
        this.jobId = jobId;
        this.jobName = jobName;
        this.pipelineUniqueId = pipelineUniqueId;
        this.pipelineName = pipelineName;
        this.id = id;
        this.status = status;
        this.errorInfo = errorInfo;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        StepWebhook that = (StepWebhook) o;
        return Objects.equals(jobId, that.jobId) &&
                Objects.equals(jobName, that.jobName) &&
                pipelineUniqueId.equals(that.pipelineUniqueId) &&
                pipelineName.equals(that.pipelineName) &&
                id.equals(that.id) &&
                status == that.status &&
                Objects.equals(errorInfo, that.errorInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), jobId, jobName, pipelineUniqueId, pipelineName, id, status, errorInfo);
    }

    @Override
    public String toString() {
        return "StepWebhook{" +
                "level=" + level +
                ", name='" + name + '\'' +
                ", jobId='" + jobId + '\'' +
                ", jobName='" + jobName + '\'' +
                ", pipelineUniqueId='" + pipelineUniqueId + '\'' +
                ", pipelineName='" + pipelineName + '\'' +
                ", id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", start='" + start + '\'' +
                ", end='" + end + '\'' +
                ", status=" + status +
                ", errorInfo=" + errorInfo +
                '}';
    }

    /**
     * Status of a build step, mapped to Datadog CI statuses.
     * Note: Datadog only supports "success" and "error" for step-level events.
     */
    public enum StepStatus {
        @JsonProperty("success")
        SUCCESS,

        @JsonProperty("error")
        ERROR
    }
}
