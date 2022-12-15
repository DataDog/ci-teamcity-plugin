package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.HostInfo;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;

import java.time.Instant;
import java.util.Date;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo.ErrorDomain.PROVIDER;

public final class TestUtils {

    public static final String TEST_API_KEY = "test-api-key";
    public static final String TEST_DD_SITE = "datad0g.com";

    public static final String DEFAULT_NAME = "Full Name";
    public static final String DEFAULT_PIPELINE_NAME = "Pipeline Name";
    public static final String DEFAULT_ID = "1";
    public static final String DEFAULT_PIPELINE_ID = "2";
    public static final String DEFAULT_BUILD_URL = "localhost/build/1";
    public static final String LOCALHOST = "localhost";
    public static final String DEFAULT_PROJECT_ID = "Project ID";
    public static final String DEFAULT_REPO_URL = "repository-url.com";
    public static final String DEFAULT_BRANCH = "main";
    public static final String DEFAULT_GIT_MESSAGE = "git message";
    public static final String DEFAULT_COMMIT_SHA = "sha";
    public static final String DEFAULT_COMMITTER_USERNAME = "committer-username";
    public static final String DEFAULT_AUTHOR_USERNAME = "author-username";
    public static final String EMPTY_AUTHOR_USERNAME = "";
    public static final String DEFAULT_COMMIT_EMAIL = "defaultemail@email.com";
    public static final String DEFAULT_CHECKOUT_DIR = "default-checkout-dir";
    public static final String DEFAULT_NODE_HOSTNAME = "default-hostname";
    public static final String DEFAULT_NODE_NAME = "default-name";
    public static final String DEFAULT_FAILURE_MESSAGE = "default-failure-message";
    public static final Status DEFAULT_STATUS = Status.NORMAL;
    public static final String DEFAULT_ERROR_TYPE = "Tests Failed";
    public static final String DEFAULT_REVISION = "revision";

    public static final int DEFAULT_QUEUE_TIME = 1000;
    public static final boolean IS_PARTIAL_RETRY = true;
    public static final boolean NO_PARTIAL_RETRY = false;

    public static final Date DEFAULT_COMMIT_DATE = Date.from(Instant.ofEpochMilli(1000));
    public static final Date DEFAULT_QUEUE_DATE = Date.from(Instant.ofEpochMilli(2000));
    public static final Date DEFAULT_START_DATE = Date.from(Instant.ofEpochMilli(3000));
    public static final Date DEFAULT_END_DATE = Date.from(Instant.ofEpochMilli(4000));

    public static String defaultUrl(SBuild build) {
        return format("%s/build/%s", LOCALHOST, build.getBuildId());
    }

    public static HostInfo defaultHostInfo() {
        return new HostInfo()
            .withHostname(DEFAULT_NODE_HOSTNAME)
            .withName(DEFAULT_NODE_NAME)
            .withWorkspace(DEFAULT_CHECKOUT_DIR);
    }

    public static ErrorInfo defaultErrorInfo() {
        return new ErrorInfo(DEFAULT_FAILURE_MESSAGE, DEFAULT_ERROR_TYPE, PROVIDER);
    }

    public static GitInfo defaultGitInfo() {
        return new GitInfo()
            .withRepositoryURL(DEFAULT_REPO_URL)
            .withDefaultBranch(DEFAULT_BRANCH)
            .withMessage(DEFAULT_GIT_MESSAGE)
            .withSha(DEFAULT_COMMIT_SHA)
            .withCommitterName(DEFAULT_COMMITTER_USERNAME)
            .withAuthorName(DEFAULT_COMMITTER_USERNAME)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorEmail(DEFAULT_COMMIT_EMAIL)
            .withCommitterEmail(DEFAULT_COMMIT_EMAIL)
            .withBranch(DEFAULT_BRANCH);
    }

    private TestUtils() { }
}
