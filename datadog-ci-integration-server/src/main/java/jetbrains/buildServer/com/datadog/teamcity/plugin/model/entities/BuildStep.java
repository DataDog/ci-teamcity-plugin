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

/**
 * Represents a single build step within a TeamCity job.
 * Contains timing information and status for individual steps like checkout, compile, test, etc.
 */
public class BuildStep {

    @JsonProperty("name")
    @Nonnull
    private final String name;

    @JsonProperty("start")
    @Nonnull
    private final String start;

    @JsonProperty("end")
    @Nonnull
    private final String end;

    @JsonProperty("duration_ms")
    private final long durationMs;

    @JsonProperty("status")
    @Nonnull
    private final StepStatus status;

    @JsonProperty("error")
    @Nullable
    private final String error;

    public BuildStep(@Nonnull String name,
                     @Nonnull String start,
                     @Nonnull String end,
                     long durationMs,
                     @Nonnull StepStatus status,
                     @Nullable String error) {
        this.name = name;
        this.start = start;
        this.end = end;
        this.durationMs = durationMs;
        this.status = status;
        this.error = error;
    }

    public String getName() {
        return name;
    }

    public String getStart() {
        return start;
    }

    public String getEnd() {
        return end;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public StepStatus getStatus() {
        return status;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuildStep buildStep = (BuildStep) o;
        return durationMs == buildStep.durationMs &&
                Objects.equals(name, buildStep.name) &&
                Objects.equals(start, buildStep.start) &&
                Objects.equals(end, buildStep.end) &&
                status == buildStep.status &&
                Objects.equals(error, buildStep.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, start, end, durationMs, status, error);
    }

    @Override
    public String toString() {
        return "BuildStep{" +
                "name='" + name + '\'' +
                ", start='" + start + '\'' +
                ", end='" + end + '\'' +
                ", durationMs=" + durationMs +
                ", status=" + status +
                ", error='" + error + '\'' +
                '}';
    }

    /**
     * Status of a build step, mapped to Datadog CI statuses.
     */
    public enum StepStatus {
        @JsonProperty("success")
        SUCCESS,

        @JsonProperty("error")
        ERROR,

        @JsonProperty("canceled")
        CANCELED,

        @JsonProperty("skipped")
        SKIPPED
    }
}
