package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    @JsonProperty("git")
    @Nullable
    protected GitInfo gitInfo;

    protected CIEntity(@Nonnull CILevel level, @Nonnull String name, @Nonnull String url, @Nonnull String start, @Nonnull String end) {
        this.level = level;
        this.name = name;
        this.url = url;
        this.start = start;
        this.end = end;
    }

    @Nonnull
    public String name() {
        return name;
    }

    @Nonnull
    public String url() {
        return url;
    }

    @Nonnull
    public String start() {
        return start;
    }

    @Nonnull
    public String end() {
        return end;
    }

    public CIEntity withGitInfo(@Nullable GitInfo gitInfo) {
        this.gitInfo = gitInfo;
        return this;
    }

    @Nullable
    public GitInfo gitInfo() {
        return gitInfo;
    }

    public enum CILevel {
        JOB, PIPELINE;

        @JsonValue
        public String toLowerCase() {
            return toString().toLowerCase();
        }
    }
}
