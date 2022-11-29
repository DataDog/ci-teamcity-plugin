package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_API_KEY_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_CI_PROVIDER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

public class DatadogClientTest {

    private static final int MAX_RETRIES_TEST = 2;
    private static final int BACKOFF_SECONDS_TEST = 0;

    @Captor
    private ArgumentCaptor<HttpEntity<String>> requestCaptor;

    private RestTemplate  restTemplateMock;
    private DatadogClient datadogClient;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        restTemplateMock = Mockito.mock(RestTemplate.class);

        RetryInformation retryInfo = new RetryInformation(MAX_RETRIES_TEST, BACKOFF_SECONDS_TEST);
        datadogClient = new DatadogClient(restTemplateMock, new ObjectMapper(), retryInfo);
    }

    @Test
    public void shouldSendWebhookForEntity() throws JsonProcessingException {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = testPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, mockApiKey)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        assertThat(requestDone.getBody()).isEqualTo(new ObjectMapper().writeValueAsString(pipeline));
    }

    @Test
    public void shouldRetryOnServerErrors() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.status(INTERNAL_SERVER_ERROR).body("Server error"))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = testPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(2))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();
    }

    @Test
    public void shouldRetryOnTransientExceptions() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenThrow(new RestClientException("Transient exception"))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = testPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(2))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isTrue();
    }

    @Test
    public void shouldNotRetryOnClientError() {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.badRequest().body("Bad request"));

        // When
        Pipeline pipeline = testPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(1))
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
        Pipeline pipeline = testPipeline();
        boolean successful = datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(3)) // 1 normal and 2 retries
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));
        assertThat(successful).isFalse();
    }

    private static Pipeline testPipeline() {
        return new Pipeline("Name", "mock.com/build", "2022-11-21T12:40:04+01:00",
                "2022-11-21T12:41:04+01:00", "unique-id", "pipeline-id",
                false, Pipeline.PipelineStatus.SUCCESS);
    }
}
