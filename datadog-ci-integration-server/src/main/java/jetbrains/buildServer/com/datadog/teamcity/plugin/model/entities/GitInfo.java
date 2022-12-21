/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class GitInfo {

    @JsonProperty("repository_url") private String repositoryURL;
    @JsonProperty("sha") private String sha;
    @JsonProperty("message") private String message;
    @JsonProperty("commit_time") private String commitTime;
    @JsonProperty("author_time") private String authorTime;
    @JsonProperty("committer_name") private String committerName;
    @JsonProperty("committer_email") private String committerEmail;
    @JsonProperty("author_name") private String authorName;
    @JsonProperty("author_email") private String authorEmail;
    @JsonProperty("default_branch") private String defaultBranch;
    @JsonProperty("branch") private String branch;
    @JsonProperty("tag") private String tag;

    public GitInfo withRepositoryURL(String repositoryURL) {
        this.repositoryURL = repositoryURL;
        return this;
    }

    public GitInfo withSha(String sha) {
        this.sha = sha;
        return this;
    }

    public GitInfo withMessage(String message) {
        this.message = message;
        return this;
    }

    public GitInfo withCommitTime(String commitTime) {
        this.commitTime = commitTime;
        return this;
    }

    public GitInfo withAuthorTime(String authorTime) {
        this.authorTime = authorTime;
        return this;
    }

    public GitInfo withCommitterName(String committerName) {
        this.committerName = committerName;
        return this;
    }

    public GitInfo withCommitterEmail(String committerEmail) {
        this.committerEmail = committerEmail;
        return this;
    }

    public GitInfo withAuthorName(String authorName) {
        this.authorName = authorName;
        return this;
    }

    public GitInfo withAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
        return this;
    }

    public GitInfo withDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
        return this;
    }

    public GitInfo withBranch(String branch) {
        this.branch = branch;
        return this;
    }

    @Override
    public String toString() {
        return "GitInfo{" +
                "repositoryURL='" + repositoryURL + '\'' +
                ", sha='" + sha + '\'' +
                ", message='" + message + '\'' +
                ", commitTime='" + commitTime + '\'' +
                ", authorTime='" + authorTime + '\'' +
                ", committerName='" + committerName + '\'' +
                ", committerEmail='" + committerEmail + '\'' +
                ", authorName='" + authorName + '\'' +
                ", authorEmail='" + authorEmail + '\'' +
                ", defaultBranch='" + defaultBranch + '\'' +
                ", branch='" + branch + '\'' +
                ", tag='" + tag + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitInfo gitInfo = (GitInfo) o;
        return Objects.equals(repositoryURL, gitInfo.repositoryURL) && Objects.equals(sha, gitInfo.sha) && Objects.equals(message, gitInfo.message) && Objects.equals(commitTime, gitInfo.commitTime) && Objects.equals(authorTime, gitInfo.authorTime) && Objects.equals(committerName, gitInfo.committerName) && Objects.equals(committerEmail, gitInfo.committerEmail) && Objects.equals(authorName, gitInfo.authorName) && Objects.equals(authorEmail, gitInfo.authorEmail) && Objects.equals(defaultBranch, gitInfo.defaultBranch) && Objects.equals(branch, gitInfo.branch) && Objects.equals(tag, gitInfo.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryURL, sha, message, commitTime, authorTime, committerName, committerEmail, authorName, authorEmail, defaultBranch, branch, tag);
    }
}
