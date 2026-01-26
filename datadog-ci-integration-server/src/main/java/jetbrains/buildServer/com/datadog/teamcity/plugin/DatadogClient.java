/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @VisibleForTesting
    protected void sendWebhooksAsync(List<Webhook> webhooks, String apiKey, String ddSite, int batchSize) {
        // Split webhooks into batches and send each batch asynchronously
        for (int i = 0; i < webhooks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, webhooks.size());
            List<Webhook> batch = webhooks.subList(i, end);
            clientExecutor.submit(() -> sendWebhookBatchWithRetries(batch, apiKey, ddSite));
        }
    }

    @VisibleForTesting
    protected boolean sendWebhookBatchWithRetries(List<Webhook> webhookBatch, String apiKey, String ddSite) {
        String url = format(WEBHOOK_INTAKE_BASE_URL, ddSite);
        String payload = serializeBatch(webhookBatch);
        HttpEntity<String> request = new HttpEntity<>(payload, getHeaders(apiKey));
        
        String batchDescription = getBatchDescription(webhookBatch);

        int currentAttempt = 0;
        while (currentAttempt <= retryInfo.maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    LOG.info(format("Successfully sent webhook batch %s to '%s'", batchDescription, url));
                    return true;
                } else if (response.getStatusCode().is5xxServerError()) {
                    LOG.warn(format("Could not send webhook batch %s to '%s'. " +
                                    "Status code: '%s', Retry number %d/%d",
                            batchDescription, url, response.getStatusCode(), currentAttempt, retryInfo.maxRetries));

                    sleepSeconds(retryInfo.backoffSeconds);
                } else {
                    // Status code is different from 5xx, so we won't retry
                    LOG.warn(format("Could not send webhook batch %s to url '%s'. " +
                                    "Status code: '%s'.", batchDescription, url, response.getStatusCode()));
                    return false;
                }
            } catch (RestClientException ex) {
                LOG.error(format("Exception occurred while sending webhook batch %s to url '%s'. " +
                                "Retry number %d/%d: ", batchDescription, url, currentAttempt, retryInfo.maxRetries), ex);
                sleepSeconds(retryInfo.backoffSeconds);
            }

            currentAttempt++;
        }

        return false;
    }
    
    private String getBatchDescription(List<Webhook> webhookBatch) {
        return format("batch of %d webhook%s, starting with id '%s'", 
            webhookBatch.size(), 
            webhookBatch.size() == 1 ? "" : "s",
            webhookBatch.get(0).id());
    }

    private HttpHeaders getHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(DD_API_KEY_HEADER, apiKey);
        headers.add(DD_CI_PROVIDER_HEADER, TEAMCITY_PROVIDER);
        return headers;
    }

    private String serializeBatch(List<Webhook> webhookBatch) {
        try {
            // Always serialize as array - Datadog API accepts both single objects and arrays
            return objectMapper.writeValueAsString(webhookBatch);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(format("Could not serialize the webhook batch: %s", webhookBatch), e);
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
