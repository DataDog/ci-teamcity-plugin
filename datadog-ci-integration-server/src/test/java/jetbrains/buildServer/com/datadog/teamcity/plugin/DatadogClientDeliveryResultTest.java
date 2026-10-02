/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import jetbrains.buildServer.com.datadog.teamcity.plugin.logs.DeliveryResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;

@RunWith(Parameterized.class)
public class DatadogClientDeliveryResultTest {
    @Parameterized.Parameters(name = "status {0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
                {400, DeliveryResult.BLOCKED, 1},
                {401, DeliveryResult.BLOCKED, 1},
                {403, DeliveryResult.BLOCKED, 1},
                {408, DeliveryResult.RETRYABLE_FAILURE, 2},
                {413, DeliveryResult.BLOCKED, 1},
                {429, DeliveryResult.RETRYABLE_FAILURE, 2},
                {500, DeliveryResult.RETRYABLE_FAILURE, 2},
                {503, DeliveryResult.RETRYABLE_FAILURE, 2}
        });
    }

    private final int status;
    private final DeliveryResult expected;
    private final int attempts;

    public DatadogClientDeliveryResultTest(int status, DeliveryResult expected, int attempts) {
        this.status = status;
        this.expected = expected;
        this.attempts = attempts;
    }

    @Test
    public void classifiesAnExhaustedLogBatch() {
        RestTemplate restTemplate = failingRestTemplate();
        DatadogClient client = client(restTemplate);

        assertThat(client.sendLogBatchWithRetriesResult(new byte[] {'[', ']'}, "key", "datad0g.com"))
                .isEqualTo(expected);

        verify(restTemplate, times(attempts)).exchange(
                eq("https://http-intake.logs.datad0g.com/api/v2/cilogs"), eq(POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    public void classifiesAnExhaustedWebhook() {
        RestTemplate restTemplate = failingRestTemplate();
        DatadogClient client = client(restTemplate);

        assertThat(client.sendWebhookPayloadWithRetries("{}", "job-1", "key", "datad0g.com"))
                .isEqualTo(expected);

        verify(restTemplate, times(attempts)).exchange(
                eq("https://webhook-intake.datad0g.com/api/v2/webhook"), eq(POST),
                any(HttpEntity.class), eq(String.class));
    }

    private RestTemplate failingRestTemplate() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        RestClientException failure = httpStatus.is5xxServerError()
                ? new HttpServerErrorException(httpStatus)
                : new HttpClientErrorException(httpStatus);
        when(restTemplate.exchange(any(String.class), eq(POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(failure);
        return restTemplate;
    }

    private DatadogClient client(RestTemplate restTemplate) {
        return new DatadogClient(restTemplate, new ObjectMapper(), new RetryInformation(1, 0),
                mock(ExecutorService.class));
    }
}
