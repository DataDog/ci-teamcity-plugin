package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

public class DatadogClient {

    private static final Logger LOG = Logger.getInstance(DatadogClient.class.getName());
    private static final String TEAMCITY_PROVIDER = "teamcity";

    // Headers
    protected static final String DD_API_KEY_HEADER = "DD-API-KEY";
    protected static final String DD_CI_PROVIDER_HEADER = "DD-CI-PROVIDER-NAME";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RetryInformation retryInfo;

    public DatadogClient(RestTemplate restTemplate, ObjectMapper objectMapper, RetryInformation retryInfo) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.retryInfo = retryInfo;
    }

    public boolean sendWebhook(CIEntity entity, String apiKey, String ddSite) {
        String url = format("https://webhook-intake.%s/api/v2/webhook", ddSite);
        HttpHeaders headers = getHeaders(apiKey);
        String payload = serialize(entity);

        return sendWebhookWithRetries(url, payload, headers);
    }

    private boolean sendWebhookWithRetries(String url, String payload, HttpHeaders headers) {
        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        int currentAttempt = 0;

        // TODO remove content and headers from logs before publishing
        while (currentAttempt <= retryInfo.maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    LOG.info(format("Successfully sent webhook to url '%s' with body '%s' and headers '%s'", url, payload, headers));
                    return true;
                } else if (response.getStatusCode().is5xxServerError()) {
                    LOG.warn(format("Could not send webhook to url '%s' with body '%s' and headers '%s'. " +
                                    "Status code: '%s', Retry number %d/%d",
                            url, payload, headers, response.getStatusCode(), currentAttempt, retryInfo.maxRetries));

                    sleepSeconds(retryInfo.backoffSeconds);
                } else {
                    // Status code is different from 5xx, so we won't retry
                    LOG.warn(format("Could not send webhook to url '%s' with body '%s' and headers '%s'. " +
                                    "Status code: '%s'.", url, payload, headers, response.getStatusCode()));
                    return false;
                }
            } catch (RestClientException ex) {
                LOG.error(format("Exception occurred while sending webhooks to to url '%s' with body '%s' and headers '%s'" +
                                ". Retry number %d/%d: ", url, payload, headers, currentAttempt, retryInfo.maxRetries), ex);
                sleepSeconds(retryInfo.backoffSeconds);
            }

            currentAttempt++;
        }

        return false;
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

    private void sleepSeconds(int seconds) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static class RetryInformation {
        private final int maxRetries;
        private final int backoffSeconds;

        public RetryInformation(int maxRetries, int backoffSeconds) {
            this.maxRetries = maxRetries;
            this.backoffSeconds = backoffSeconds;
        }
    }
}
