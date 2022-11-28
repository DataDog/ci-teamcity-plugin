package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nonnull;

/**
 * This class represents the abstraction for CI objects (pipelines, jobs).
 */
public class CIEntity {

    @JsonProperty
    @Nonnull
    protected final CILevel level;

    @JsonProperty
    @Nonnull
    protected final String name;

    @JsonProperty
    @Nonnull
    protected final String url;

    @JsonProperty
    @Nonnull
    protected final String start;

    @JsonProperty
    @Nonnull
    protected final String end;

    protected CIEntity(@Nonnull CILevel level, @Nonnull String name, @Nonnull String url, @Nonnull String start, @Nonnull String end) {
        this.level = level;
        this.name = name;
        this.url = url;
        this.start = start;
        this.end = end;
    }

    public enum CILevel {
        JOB, PIPELINE;

        @JsonValue
        public String toLowerCase() {
            return toString().toLowerCase();
        }
    }
}
