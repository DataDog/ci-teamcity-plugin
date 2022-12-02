package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity.CILevel.JOB;

public class Job extends CIEntity {

    @JsonProperty("pipeline_unique_id")
    @Nonnull
    private final String pipelineID;

    @JsonProperty("pipeline_name")
    @Nonnull
    private final String pipelineName;

    @JsonProperty
    @Nonnull
    private final String id;

    @JsonProperty
    @Nonnull
    private final JobStatus status;

    @JsonProperty("node")
    @Nullable
    protected HostInfo hostInfo; // Not available for pipelines as composite builds are not run in agents

    public Job(@Nonnull String name,
               @Nonnull String url,
               @Nonnull String start,
               @Nonnull String end,
               @Nonnull String pipelineID,
               @Nonnull String pipelineName,
               @Nonnull String id,
               @Nonnull JobStatus status) {
        super(JOB, name, url, start, end);
        this.pipelineID = pipelineID;
        this.pipelineName = pipelineName;
        this.id = id;
        this.status = status;
    }

    @Override
    public String toString() {
        return "Job{" +
                "pipelineID='" + pipelineID + '\'' +
                ", pipelineName='" + pipelineName + '\'' +
                ", id='" + id + '\'' +
                ", status=" + status +
                ", level=" + level +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", start='" + start + '\'' +
                ", end='" + end + '\'' +
                "} " + super.toString();
    }

    @Nonnull
    public String pipelineID() {
        return pipelineID;
    }

    @Nonnull
    public String id() {
        return id;
    }

    @Nullable
    public HostInfo hostInfo() {
        return hostInfo;
    }

    public Job withHostInfo(@Nullable HostInfo hostInfo) {
        this.hostInfo = hostInfo;
        return this;
    }

    public enum JobStatus {
        SUCCESS, ERROR;

        @JsonValue
        public String toLowerCase() {
            return toString().toLowerCase();
        }
    }

    public static class HostInfo {
        @JsonProperty("hostname") private String hostname;
        @JsonProperty("name") private String name;
        @JsonProperty("workspace") private String workspace;

        public String hostname() {
            return hostname;
        }

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

        public String workspace() {
            return workspace;
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
    }

}
