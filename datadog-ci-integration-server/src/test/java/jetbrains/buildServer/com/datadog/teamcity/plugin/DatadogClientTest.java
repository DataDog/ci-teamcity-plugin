package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.HostInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline.RelatedPipeline;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_API_KEY_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_CI_PROVIDER_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BUILD_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_CHECKOUT_DIR;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_EMAIL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_FAILURE_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_GIT_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_HOSTNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PIPELINE_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PIPELINE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_QUEUE_TIME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REPO_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain.PROVIDER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline.PipelineStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;


@RunWith(MockitoJUnitRunner.class)
public class DatadogClientTest {

    private static final RetryInformation RETRY_INFO = new RetryInformation(2, 0);

    @Captor private ArgumentCaptor<HttpEntity<String>> requestCaptor;

    @Mock private RestTemplate  restTemplateMock;

    private DatadogClient datadogClient;

    @Before
    public void setUp() {
        ObjectMapper mapper = new DatadogConfiguration().objectMapper();
        datadogClient = new DatadogClient(restTemplateMock, mapper, RETRY_INFO);
    }

    @Test
    public void shouldSendWebhookForPipeline() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = defaultPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(formatJson(body)).isEqualTo(formatJson(expectedJson));
    }

    @Test
    public void shouldRetryOnServerErrors() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.status(INTERNAL_SERVER_ERROR).body("Server error"))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = defaultPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(2))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();

        System.out.println("Actual: " + formatJson(body));
        System.out.println("Expected: " + formatJson(expectedJson));

        assertThat(formatJson(body)).isEqualTo(formatJson(expectedJson));
    }

    @Test
    public void shouldRetryOnTransientExceptions() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenThrow(new RestClientException("Transient exception"))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = defaultPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(2))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(formatJson(body)).isEqualTo(formatJson(expectedJson));
    }

    @Test
    public void shouldNotRetryOnClientError() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.badRequest().body("Bad request"));

        // When
        Pipeline pipeline = defaultPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isFalse();
    }

    @Test
    public void shouldStopRetrying() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.status(INTERNAL_SERVER_ERROR).body("Server error"));

        // When
        Pipeline pipeline = defaultPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        verify(restTemplateMock, times(3)) // 1 normal and 2 retries
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isFalse();
    }

    @Test
    public void shouldSendCompleteWebhookForPipeline() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = (Pipeline) defaultPipeline().withGitInfo(defaultGitInfo());
        pipeline.setPreviousAttempt(new RelatedPipeline(DEFAULT_ID, DEFAULT_BUILD_URL));
        boolean successful = datadogClient.sendWebhook(pipeline, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("complete-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(formatJson(body)).isEqualTo(formatJson(expectedJson));
    }

    @Test
    public void shouldSendCompleteWebhookForJob() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Job job = completeJob();
        boolean successful = datadogClient.sendWebhook(job, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("complete-job.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(formatJson(body)).isEqualTo(formatJson(expectedJson));
    }

    private static Pipeline defaultPipeline() {
        return new Pipeline(
                DEFAULT_NAME,
                DEFAULT_BUILD_URL,
                DEFAULT_START_DATE,
                DEFAULT_END_DATE,
                DEFAULT_ID,
                DEFAULT_ID,
                false,
                SUCCESS
        );
    }

    private static Job completeJob() {
        Job job = new Job(
                DEFAULT_NAME,
                DEFAULT_BUILD_URL,
                DEFAULT_START_DATE,
                DEFAULT_END_DATE,
                DEFAULT_PIPELINE_ID,
                DEFAULT_PIPELINE_NAME,
                DEFAULT_ID,
                JobStatus.SUCCESS,
                DEFAULT_QUEUE_TIME
        );

        return (Job) job
                .withErrorInfo(new ErrorInfo(DEFAULT_FAILURE_MESSAGE, "Failed Tests", PROVIDER))
                .withHostInfo(new HostInfo()
                        .withHostname(DEFAULT_NODE_HOSTNAME)
                        .withName(DEFAULT_NODE_NAME)
                        .withWorkspace(DEFAULT_CHECKOUT_DIR))
                .withGitInfo(defaultGitInfo());
    }

    private static GitInfo defaultGitInfo() {
        return new GitInfo()
                .withRepositoryURL(DEFAULT_REPO_URL)
                .withDefaultBranch(DEFAULT_BRANCH)
                .withMessage(DEFAULT_GIT_MESSAGE)
                .withSha(DEFAULT_COMMIT_SHA)
                .withCommitterName(DEFAULT_COMMIT_USERNAME)
                .withAuthorName(DEFAULT_COMMIT_USERNAME)
                .withCommitTime(DEFAULT_COMMIT_DATE)
                .withAuthorTime(DEFAULT_COMMIT_DATE)
                .withAuthorEmail(DEFAULT_COMMIT_EMAIL)
                .withCommitterEmail(DEFAULT_COMMIT_EMAIL)
                .withBranch(DEFAULT_BRANCH);
    }

    private String loadJson(String fileName) {
        try {
            URI uri = getClass().getClassLoader().getResource(fileName).toURI();
            return new String(Files.readAllBytes(Paths.get(uri)), StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static String formatJson(String input) {
        return input.replaceAll("\\s", "").replaceAll("\\+01:00", "Z");
    }
}
