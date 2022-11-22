package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.CIEntity;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import static java.lang.String.format;

public class DatadogClient {

    private static final Logger LOG = Logger.getInstance(DatadogClient.class.getName());
    private static final String TEAMCITY_PROVIDER = "teamcity";

    // Headers
    protected static final String DD_API_KEY_HEADER = "DD-API-KEY";
    protected static final String DD_CI_PROVIDER_HEADER = "DD-CI-PROVIDER-NAME";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DatadogClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendWebhook(CIEntity entity, String apiKey, String ddSite) {
        String url = format("https://webhook-intake.%s/api/v2/webhook", ddSite);
        HttpHeaders headers = getHeaders(apiKey);
        String payload = serialize(entity);

        // TODO this will be enhanced to retry on transient failures (CIAPP-5361)
        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            LOG.info(format("Successfully sent webhook to url '%s' with body '%s' and headers '%s'", url, payload, headers));
        } else {
            LOG.info(format("Could not send webhook to url '%s' with body '%s' and headers '%s'. Status code: '%s'",
                    url, payload, headers, response.getStatusCode()));
        }
    }

    private HttpHeaders getHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(DD_API_KEY_HEADER, apiKey);
        headers.add(DD_CI_PROVIDER_HEADER, TEAMCITY_PROVIDER);
        return headers;
    }

    private String serialize(CIEntity entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(format("Could not serialize the content of the entity: %s", entity), e);
        }
    }
}
