package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.messages.Status;

import java.time.Instant;
import java.util.Date;

public final class TestUtils {

    public static final String TEST_API_KEY = "test-api-key";
    public static final String TEST_DD_SITE = "datad0g.com";

    public static final String DEFAULT_NAME = "Full Name";
    public static final String DEFAULT_PIPELINE_NAME = "Pipeline Name";
    public static final String DEFAULT_ID = "1";
    public static final String DEFAULT_PIPELINE_ID = "2";
    public static final String DEFAULT_BUILD_URL = "localhost/build/1";
    public static final String DEFAULT_PROJECT_ID = "Project ID";
    public static final String DEFAULT_REPO_URL = "repository-url.com";
    public static final String DEFAULT_BRANCH = "main";
    public static final String DEFAULT_GIT_MESSAGE = "git message";
    public static final String DEFAULT_COMMIT_SHA = "sha";
    public static final String DEFAULT_COMMIT_USERNAME = "username";
    public static final String DEFAULT_COMMIT_EMAIL = "defaultemail@email.com";
    public static final String DEFAULT_CHECKOUT_DIR = "default-checkout-dir";
    public static final String DEFAULT_NODE_HOSTNAME = "default-hostname";
    public static final String DEFAULT_NODE_NAME = "default-name";
    public static final String DEFAULT_FAILURE_MESSAGE = "default-failure-message";
    public static final Status DEFAULT_STATUS = Status.NORMAL;

    public static final int DEFAULT_QUEUE_TIME = 100;

    public static final Date DEFAULT_QUEUE_DATE = Date.from(Instant.ofEpochMilli(998));
    public static final Date DEFAULT_START_DATE = Date.from(Instant.ofEpochMilli(1000));
    public static final Date DEFAULT_END_DATE = Date.from(Instant.ofEpochMilli(1005));
    public static final Date DEFAULT_COMMIT_DATE = Date.from(Instant.ofEpochMilli(995));

    private TestUtils() { }
}
