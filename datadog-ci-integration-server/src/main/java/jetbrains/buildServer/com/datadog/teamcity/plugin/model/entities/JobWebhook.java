/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Arrays.asList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook.CILevel.JOB;

public class JobWebhook extends Webhook {

    @JsonProperty("pipeline_unique_id")
    @Nonnull
    private final String pipelineID;

    @JsonProperty("pipeline_name")
    @Nonnull
    private final String pipelineName;

    @JsonProperty("id")
    @Nonnull
    private final String id;

    @JsonProperty("status")
    @Nonnull
    private final JobStatus status;

    @JsonProperty("queue_time")
    private final long queueTimeMs;

    private long dependencyWaitTimeMs;

    private long preparationTimeMs;

    private final Map<String, Long> queueWaitReasonTimesMs;

    @JsonProperty("dependencies")
    @Nullable
    private List<String> dependenciesIds;

    @JsonProperty("node")
    @Nullable
    protected HostInfo hostInfo; // Not available for pipelines as composite builds are not run in agents

    @JsonProperty("error")
    @Nullable
    protected ErrorInfo errorInfo;

    public JobWebhook(@Nonnull String name,
                      @Nonnull String url,
                      @Nonnull String start,
                      @Nonnull String end,
                      @Nonnull String pipelineID,
                      @Nonnull String pipelineName,
                      @Nonnull String id,
                      @Nonnull JobStatus status,
                      long queueTimeMs,
                      Map<String, Long> queueWaitReasonTimesMs) {
        super(JOB, name, url, start, end);
        this.pipelineID = pipelineID;
        this.pipelineName = pipelineName;
        this.id = id;
        this.status = status;
        this.queueTimeMs = queueTimeMs;
        this.queueWaitReasonTimesMs = queueWaitReasonTimesMs;
    }

    public void setDependenciesIds(@Nonnull List<String> dependenciesIds) {
        this.dependenciesIds = dependenciesIds;
    }

    public void setDependencyWaitTimeMs(long dependencyWaitTimeMs) {
        this.dependencyWaitTimeMs = dependencyWaitTimeMs;
    }

    public void setPreparationTimeMs(long preparationTimeMs) {
        this.preparationTimeMs = preparationTimeMs;
    }

    @JsonProperty("metrics")
    public List<String> metrics() {
        List<String> metrics = new ArrayList<>(asList(
            "teamcity.dependency_wait_time:" + dependencyWaitTimeMs / 1000.0,
            "teamcity.preparation_time:" + preparationTimeMs / 1000.0));
        queueWaitReasonTimesMs.forEach((reason, duration) -> metrics.add("teamcity.queue_wait." + reason + ":" + duration));
        return metrics;
    }

    public void setErrorInfo(@Nonnull ErrorInfo errorInfo) {
        this.errorInfo = errorInfo;
    }

    public void setHostInfo(@Nonnull HostInfo hostInfo) {
        this.hostInfo = hostInfo;
    }

    @Nonnull
    public JobStatus status() {
        return status;
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
        JobWebhook that = (JobWebhook) o;
        return queueTimeMs == that.queueTimeMs && dependencyWaitTimeMs == that.dependencyWaitTimeMs && preparationTimeMs == that.preparationTimeMs && queueWaitReasonTimesMs.equals(that.queueWaitReasonTimesMs) && pipelineID.equals(that.pipelineID) && pipelineName.equals(that.pipelineName) && id.equals(that.id) && status == that.status && Objects.equals(dependenciesIds, that.dependenciesIds) && Objects.equals(hostInfo, that.hostInfo) && Objects.equals(errorInfo, that.errorInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), pipelineID, pipelineName, id, status, queueTimeMs, dependencyWaitTimeMs, preparationTimeMs, queueWaitReasonTimesMs, dependenciesIds, hostInfo, errorInfo);
    }

    @Override
    public String toString() {
        return "JobWebhook{" +
            "pipelineID='" + pipelineID + '\'' +
            ", pipelineName='" + pipelineName + '\'' +
            ", id='" + id + '\'' +
            ", status=" + status +
            ", queueTimeMs=" + queueTimeMs +
            ", dependencyWaitTimeMs=" + dependencyWaitTimeMs +
            ", preparationTimeMs=" + preparationTimeMs +
            ", queueWaitReasonTimesMs=" + queueWaitReasonTimesMs +
            ", dependenciesIds=" + dependenciesIds +
            ", hostInfo=" + hostInfo +
            ", errorInfo=" + errorInfo +
            ", level=" + level +
            ", name='" + name + '\'' +
            ", url='" + url + '\'' +
            ", start='" + start + '\'' +
            ", end='" + end + '\'' +
            ", gitInfo=" + gitInfo +
            "} " + super.toString();
    }

    public enum JobStatus {
        @JsonProperty("success") SUCCESS,
        @JsonProperty("error") ERROR,
        @JsonProperty("canceled") CANCELED
    }

    public static class HostInfo {
        @JsonProperty("hostname") private String hostname;
        @JsonProperty("name") private String name;
        @JsonProperty("workspace") private String workspace;


        public HostInfo withHostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        public String name() {
            return name;
        }

        public HostInfo withName(String name) {
            this.name = name;
            return this;
        }

        public HostInfo withWorkspace(String workspace) {
            this.workspace = workspace;
            return this;
        }

        @Override
        public String toString() {
            return "HostInfo{" +
                    "hostname='" + hostname + '\'' +
                    ", name='" + name + '\'' +
                    ", workspace='" + workspace + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HostInfo hostInfo = (HostInfo) o;
            return Objects.equals(hostname, hostInfo.hostname) && Objects.equals(name, hostInfo.name) && Objects.equals(workspace, hostInfo.workspace);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hostname, name, workspace);
        }
    }

    public static class ErrorInfo {
        @JsonProperty("message") private final String message;
        @JsonProperty("type") private final String type;
        @JsonProperty("domain") private final ErrorDomain domain;

        public ErrorInfo(String message, String type, ErrorDomain domain) {
            this.message = message;
            this.type = type;
            this.domain = domain;
        }

        @Override
        public String toString() {
            return "ErrorInfo{" +
                    "message='" + message + '\'' +
                    ", type='" + type + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ErrorInfo errorInfo = (ErrorInfo) o;
            return Objects.equals(message, errorInfo.message) && Objects.equals(type, errorInfo.type) && domain == errorInfo.domain;
        }

        @Override
        public int hashCode() {
            return Objects.hash(message, type, domain);
        }

        public enum ErrorDomain {
            @JsonProperty("provider") PROVIDER,
            @JsonProperty("user") USER
        }
    }

}
