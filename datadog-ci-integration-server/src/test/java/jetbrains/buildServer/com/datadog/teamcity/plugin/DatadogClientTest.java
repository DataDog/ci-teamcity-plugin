package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_API_KEY_HEADER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.DD_CI_PROVIDER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;

public class DatadogClientTest {

    @Captor
    private ArgumentCaptor<HttpEntity<String>> requestCaptor;

    private RestTemplate  restTemplateMock;
    private DatadogClient datadogClient;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        restTemplateMock = Mockito.mock(RestTemplate.class);
        datadogClient = new DatadogClient(restTemplateMock, new ObjectMapper());
    }

    @Test
    public void shouldSendWebhookForEntity() throws JsonProcessingException {
        // Setup
        String mockApiKey = "mock-api-key";
        when(restTemplateMock.exchange(anyString(), eq(POST), any(), Matchers.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("Successful Request"));

        // When
        Pipeline pipeline = testPipeline();
        datadogClient.sendWebhook(pipeline, mockApiKey, "datad0g.com");

        // Then
        Mockito.verify(restTemplateMock, times(1))
                .exchange(eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST), requestCaptor.capture(), eq(String.class));

        HttpEntity<String> requestDone = requestCaptor.getValue();
        assertThat(requestDone.getHeaders().toSingleValueMap())
                .hasSize(3)
                .containsEntry("Content-Type", MediaType.APPLICATION_JSON.toString())
                .containsEntry(DD_API_KEY_HEADER, mockApiKey)
                .containsEntry(DD_CI_PROVIDER_HEADER, "teamcity");

        assertThat(requestDone.getBody()).isEqualTo(new ObjectMapper().writeValueAsString(pipeline));
    }

    private static Pipeline testPipeline() {
        return new Pipeline("Name", "mock.com/build", "2022-11-21T12:40:04+01:00",
                "2022-11-21T12:41:04+01:00", "unique-id", "pipeline-id",
                false, Pipeline.PipelineStatus.SUCCESS);
    }
}
