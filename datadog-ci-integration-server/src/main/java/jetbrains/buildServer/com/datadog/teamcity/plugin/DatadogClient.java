/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

public class DatadogClient {

    private static final Logger LOG = Logger.getInstance(DatadogClient.class.getName());
    private static final String TEAMCITY_PROVIDER = "teamcity";
    private static final String WEBHOOK_INTAKE_BASE_URL = "https://webhook-intake.%s/api/v2/webhook";
    private static final String LOG_INTAKE_BASE_URL = "https://http-intake.logs.%s/api/v2/cilogs";

    protected static final String DD_API_KEY_HEADER = "DD-API-KEY";
    protected static final String DD_CI_PROVIDER_HEADER = "DD-CI-PROVIDER-NAME";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RetryInformation retryInfo;
    private final ExecutorService clientExecutor;

    public DatadogClient(RestTemplate restTemplate, ObjectMapper objectMapper, RetryInformation retryInfo, ExecutorService clientExecutor) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.retryInfo = retryInfo;
        this.clientExecutor = clientExecutor;
    }

    public void sendWebhooksAsync(List<Webhook> webhooks, String apiKey, String ddSite) {
        for (Webhook webhook : webhooks) {
            clientExecutor.submit(() -> sendWebhookWithRetries(webhook, apiKey, ddSite));
        }
    }

    public boolean sendLogBatchWithRetries(byte[] payload, String apiKey, String ddSite) {
        if (!"datadoghq.com".equals(ddSite) && !"datad0g.com".equals(ddSite)) {
            LOG.warn(format("CI log intake is not available for site '%s'", ddSite));
            return false;
        }

        String url = format(LOG_INTAKE_BASE_URL, ddSite);
        HttpEntity<byte[]> request = new HttpEntity<>(payload, getHeaders(apiKey));

        for (int attempt = 0; attempt <= retryInfo.maxRetries; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return true;
                }
                if (!shouldRetry(response.getStatusCodeValue())) {
                    LOG.warn(format("CI log intake rejected a batch with status %d", response.getStatusCodeValue()));
                    return false;
                }
            } catch (HttpStatusCodeException ex) {
                if (!shouldRetry(ex.getRawStatusCode())) {
                    LOG.warn(format("CI log intake rejected a batch with status %d", ex.getRawStatusCode()));
                    return false;
                }
            } catch (RestClientException ex) {
                LOG.warn(format("Could not send a CI log batch, attempt %d/%d", attempt + 1, retryInfo.maxRetries + 1), ex);
            }

            if (attempt < retryInfo.maxRetries) {
                sleepSeconds(retryInfo.backoffSeconds);
            }
        }

        return false;
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    public boolean sendWebhookWithRetries(Webhook webhook, String apiKey, String ddSite) {
        String url = format(WEBHOOK_INTAKE_BASE_URL, ddSite);
        String payload = serialize(webhook);
        HttpEntity<String> request = new HttpEntity<>(payload, getHeaders(apiKey));

        int currentAttempt = 0;
        while (currentAttempt <= retryInfo.maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    LOG.info(format("Successfully sent webhook with id '%s' to '%s'", webhook.id(), url));
                    return true;
                } else if (response.getStatusCode().is5xxServerError()) {
                    LOG.warn(format("Could not send webhook with id '%s' to '%s'. " +
                                    "Status code: '%s', Retry number %d/%d",
                            webhook.id(), url, response.getStatusCode(), currentAttempt, retryInfo.maxRetries));

                    sleepSeconds(retryInfo.backoffSeconds);
                } else {
                    // Status code is different from 5xx, so we won't retry
                    LOG.warn(format("Could not send webhook with id '%s' to url '%s'. " +
                                    "Status code: '%s'.", webhook.id(), url, response.getStatusCode()));
                    return false;
                }
            } catch (RestClientException ex) {
                LOG.error(format("Exception occurred while sending webhooks with id '%s' to url '%s'. " +
                                "Retry number %d/%d: ", webhook.id(), url, currentAttempt, retryInfo.maxRetries), ex);
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

    private String serialize(Webhook entity) {
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
