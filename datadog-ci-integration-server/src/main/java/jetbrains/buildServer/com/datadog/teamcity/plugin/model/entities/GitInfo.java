package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    public GitInfo withTag(String tag) {
        this.tag = tag;
        return this;
    }

    public String repositoryURL() {
        return repositoryURL;
    }

    public String sha() {
        return sha;
    }

    public String commitTime() {
        return commitTime;
    }

    public String authorTime() {
        return authorTime;
    }

    public String committerName() {
        return committerName;
    }

    public String committerEmail() {
        return committerEmail;
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    public String branch() {
        return branch;
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
}
