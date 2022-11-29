package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity.CILevel.PIPELINE;

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

    @JsonProperty("previous_attempt")
    @Nullable
    private RelatedPipeline previousAttempt;

    public Pipeline(@Nonnull String name,
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

    @Nonnull
    public String name() { return name; }

    public boolean isPartialRetry() {
        return partialRetry;
    }

    @Nullable
    public RelatedPipeline previousAttempt() {
        return previousAttempt;
    }

    public void setPreviousAttempt(RelatedPipeline previousAttempt) {
        this.previousAttempt = previousAttempt;
    }

    public enum PipelineStatus {
        SUCCESS, ERROR;

        @JsonValue
        public String toLowerCase() {
            return toString().toLowerCase();
        }
    }

    public static class RelatedPipeline {
        @JsonProperty private final String id;
        @JsonProperty private final String url;

        public RelatedPipeline(String id, String url) {
            this.id = id;
            this.url = url;
        }

        public String id() {
            return id;
        }
    }
}
