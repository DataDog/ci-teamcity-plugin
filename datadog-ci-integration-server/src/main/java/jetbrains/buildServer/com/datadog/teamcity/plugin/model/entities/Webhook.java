package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public class Webhook {

    @JsonProperty("level")
    @Nonnull
    protected final CILevel level;

    @JsonProperty("name")
    @Nonnull
    protected final String name;

    @JsonProperty("url")
    @Nonnull
    protected final String url;

    @JsonProperty("start")
    @Nonnull
    protected final String start;

    @JsonProperty("end")
    @Nonnull
    protected final String end;

    @JsonProperty("git")
    @Nullable
    protected GitInfo gitInfo;

    @JsonProperty("tags")
    @Nullable
    protected List<String> tags;

    protected Webhook(@Nonnull CILevel level, @Nonnull String name, @Nonnull String url, @Nonnull String start, @Nonnull String end) {
        this.level = level;
        this.name = name;
        this.url = url;
        this.start = start;
        this.end = end;
    }

    public void setGitInfo(@Nullable GitInfo gitInfo) {
        this.gitInfo = gitInfo;
    }

    public void setTags(@Nullable List<String> tags) {
        this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Webhook webhook = (Webhook) o;
        return level == webhook.level && name.equals(webhook.name) && url.equals(webhook.url) && start.equals(webhook.start) && end.equals(webhook.end) && Objects.equals(gitInfo, webhook.gitInfo) && Objects.equals(tags, webhook.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, name, url, start, end, gitInfo);
    }

    public enum CILevel {
        @JsonProperty("job") JOB,
        @JsonProperty("pipeline") PIPELINE
    }
}
