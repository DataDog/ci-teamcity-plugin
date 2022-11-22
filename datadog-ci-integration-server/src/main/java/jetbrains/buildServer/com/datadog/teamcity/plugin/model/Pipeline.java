package jetbrains.buildServer.com.datadog.teamcity.plugin.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.CIEntity.CILevel.PIPELINE;

public class Pipeline extends CIEntity {

    @JsonProperty("unique_id")
    @Nonnull
    private final String uniqueId;

    @JsonProperty("pipeline_id")
    @Nonnull
    private final String pipelineId;

    @JsonProperty("partial_retry")
    private final boolean partialRetry;

    @JsonProperty
    @Nonnull
    private final PipelineStatus status;

    //TODO add comment here
    public Pipeline(@NotNull String name,
                    @NotNull String url,
                    @NotNull String start,
                    @NotNull String end,
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
        return "Pipeline{" +
                "uniqueId='" + uniqueId + '\'' +
                ", pipelineId='" + pipelineId + '\'' +
                ", partialRetry=" + partialRetry +
                ", status=" + status +
                ", level=" + level +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", start='" + start + '\'' +
                ", end='" + end + '\'' +
                "} " + super.toString();
    }

    @Nonnull
    public String uniqueId() {
        return uniqueId;
    }

    public enum PipelineStatus {
        @JsonProperty("success") SUCCESS, @JsonProperty("error") ERROR
    }
}
