/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
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
public class DatadogClientLogBatchTest {

    @Parameterized.Parameters(name = "status {0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
                {400, false, 1},
                {401, false, 1},
                {403, false, 1},
                {408, true, 2},
                {413, false, 1},
                {429, true, 2},
                {500, true, 2},
                {503, true, 2}
        });
    }

    private final int status;
    private final boolean expectedSuccess;
    private final int expectedAttempts;

    public DatadogClientLogBatchTest(int status, boolean expectedSuccess, int expectedAttempts) {
        this.status = status;
        this.expectedSuccess = expectedSuccess;
        this.expectedAttempts = expectedAttempts;
    }

    @Test
    public void retriesOnlyTransientHttpErrors() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DatadogClient client = client(restTemplate);
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        RestClientException failure = httpStatus.is5xxServerError()
                ? new HttpServerErrorException(httpStatus)
                : new HttpClientErrorException(httpStatus);
        when(restTemplate.exchange(any(String.class), eq(POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(failure).thenReturn(ResponseEntity.ok("accepted"));
        byte[] payload = "[{\"message\":\"test\"}]".getBytes(StandardCharsets.UTF_8);

        boolean sent = client.sendLogBatchWithRetries(payload, "api-key", "datad0g.com");

        assertThat(sent).isEqualTo(expectedSuccess);
        ArgumentCaptor<HttpEntity> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(expectedAttempts)).exchange(
                eq("https://http-intake.logs.datad0g.com/api/v2/cilogs"), eq(POST), requests.capture(), eq(String.class));
        for (HttpEntity request : requests.getAllValues()) {
            assertThat(request.getBody()).isEqualTo(payload);
            assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(request.getHeaders().getFirst("DD-API-KEY")).isEqualTo("api-key");
            assertThat(request.getHeaders().getFirst("DD-CI-PROVIDER-NAME")).isEqualTo("teamcity");
        }
    }

    @Test
    public void retriesTransportErrors() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("temporary"))
                .thenReturn(ResponseEntity.ok("accepted"));

        assertThat(client(restTemplate).sendLogBatchWithRetries(new byte[] {'[', ']'}, "api-key", "datad0g.com"))
                .isTrue();

        verify(restTemplate, times(2)).exchange(any(String.class), eq(POST), any(HttpEntity.class), eq(String.class));
    }

    private DatadogClient client(RestTemplate restTemplate) {
        return new DatadogClient(restTemplate, new ObjectMapper(), new RetryInformation(1, 0),
                mock(ExecutorService.class));
    }
}
