package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Collections.singletonList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_API_KEY_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_CI_PROVIDER_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BUILD_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PIPELINE_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PIPELINE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_QUEUE_TIME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.NO_PARTIAL_RETRY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultErrorInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultGitInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultHostInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook.PipelineStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;


@RunWith(MockitoJUnitRunner.class)
public class DatadogClientTest {

    private static final RetryInformation RETRY_INFO = new RetryInformation(2, 0);
    private static final int TEST_TIMEOUT_MS = 30_000;
    private static final String TEST_WEBHOOK_INTAKE = "https://webhook-intake.datad0g.com/api/v2/webhook";

    @Captor
    private ArgumentCaptor<HttpEntity<String>> requestCaptor;

    @Mock
    private RestTemplate restTemplateMock;

    private DatadogClient datadogClient;

    @Before
    public void setUp() {
        ObjectMapper mapper = new DatadogConfiguration().objectMapper();
        ExecutorService executorService = Executors.newFixedThreadPool(1);

        datadogClient = new DatadogClient(restTemplateMock, mapper, RETRY_INFO, executorService);
    }

    @Test
    public void shouldSendWebhookAsync() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        datadogClient.sendWebhooksAsync(singletonList(pipelineWebhook), TEST_API_KEY, TEST_DD_SITE);

        verify(restTemplateMock, timeout(TEST_TIMEOUT_MS).times(1))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    @Test
    public void shouldSendMultipleWebhooksAsync() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        List<Webhook> webhooks = Arrays.asList(completeJob(), completePipeline());
        datadogClient.sendWebhooksAsync(webhooks, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, timeout(TEST_TIMEOUT_MS).times(2))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));

        String expectedJobJson = loadJson("complete-job.json");
        String expectedPipelineJson = loadJson("complete-pipeline.json");

        List<HttpEntity<String>> requests = requestCaptor.getAllValues();
        assertThat(requests).hasSize(2)
            .anyMatch(req -> removeWhitespaces(req.getBody()).equals(removeWhitespaces(expectedJobJson)))
            .anyMatch(req -> removeWhitespaces(req.getBody()).equals(removeWhitespaces(expectedPipelineJson)));

    }

    @Test
    public void shouldSendWebhookForPipeline() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
            .hasSize(3)
            .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
            .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
            .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    @Test
    public void shouldRetryOnServerErrors() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.status(INTERNAL_SERVER_ERROR).body("Server error"))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(2))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
            .hasSize(3)
            .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
            .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
            .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    @Test
    public void shouldRetryOnTransientExceptions() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenThrow(new RestClientException("Transient exception"))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(2))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
            .hasSize(3)
            .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
            .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
            .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("default-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    @Test
    public void shouldNotRetryOnClientError() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.badRequest().body("Bad request"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, mockApiKey, "datad0g.com");

        // Then
        verify(restTemplateMock, times(1))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isFalse();
    }

    @Test
    public void shouldStopRetrying() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.status(INTERNAL_SERVER_ERROR).body("Server error"));

        // When
        PipelineWebhook pipelineWebhook = defaultPipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, mockApiKey, "datad0g.com");

        // Then
        verify(restTemplateMock, times(3)) // 1 normal and 2 retries
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isFalse();
    }

    @Test
    public void shouldSendCompleteWebhookForPipeline() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        PipelineWebhook pipelineWebhook = completePipeline();
        boolean successful = datadogClient.sendWebhookWithRetries(pipelineWebhook, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
            .hasSize(3)
            .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
            .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
            .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("complete-pipeline.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    @Test
    public void shouldSendCompleteWebhookForJob() {
        // Setup
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
            .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        JobWebhook jobWebhook = completeJob();
        boolean successful = datadogClient.sendWebhookWithRetries(jobWebhook, TEST_API_KEY, TEST_DD_SITE);

        // Then
        verify(restTemplateMock, times(1))
            .exchange(eq(TEST_WEBHOOK_INTAKE), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
            .hasSize(3)
            .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
            .containsEntry(DD_API_KEY_HEADER, TEST_API_KEY)
            .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        String expectedJson = loadJson("complete-job.json");
        String body = requestCaptor.getValue().getBody();
        assertThat(removeWhitespaces(body)).isEqualTo(removeWhitespaces(expectedJson));
    }

    private static PipelineWebhook defaultPipeline() {
        return new PipelineWebhook(
            DEFAULT_NAME,
            DEFAULT_BUILD_URL,
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            DEFAULT_ID,
            DEFAULT_ID,
            NO_PARTIAL_RETRY,
            SUCCESS
        );
    }

    private static PipelineWebhook completePipeline() {
        PipelineWebhook pipelineWebhook = defaultPipeline();
        pipelineWebhook.setGitInfo(defaultGitInfo());
        return pipelineWebhook;
    }

    private static JobWebhook completeJob() {
        JobWebhook jobWebhook = new JobWebhook(
            DEFAULT_NAME,
            DEFAULT_BUILD_URL,
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            DEFAULT_PIPELINE_ID,
            DEFAULT_PIPELINE_NAME,
            DEFAULT_ID,
            JobStatus.SUCCESS,
            DEFAULT_QUEUE_TIME
        );

        jobWebhook.setErrorInfo(defaultErrorInfo());
        jobWebhook.setHostInfo(defaultHostInfo());
        jobWebhook.setGitInfo(defaultGitInfo());
        return jobWebhook;
    }

    private String loadJson(String fileName) {
        try {
            URI uri = getClass().getClassLoader().getResource(fileName).toURI();
            return new String(Files.readAllBytes(Paths.get(uri)), StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static String removeWhitespaces(String input) {
        return input.replaceAll("\\s", "");
    }
}
