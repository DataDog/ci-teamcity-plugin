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
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;

@RunWith(Parameterized.class)
public class DatadogClientLogSiteTest {

    @Parameterized.Parameters(name = "site {0}")
    public static Collection<Object[]> sites() {
        return Arrays.asList(new Object[][] {
                {"datadoghq.com"},
                {"us3.datadoghq.com"},
                {"us5.datadoghq.com"},
                {"datadoghq.eu"},
                {"ap1.datadoghq.com"},
                {"ap2.datadoghq.com"},
                {"uk1.datadoghq.com"},
                {"ddog-gov.com"},
                {"us2.ddog-gov.com"},
                {"datad0g.com"}
        });
    }

    private final String site;

    public DatadogClientLogSiteTest(String site) {
        this.site = site;
    }

    @Test
    public void routesLogBatchToConfiguredSite() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("accepted"));
        DatadogClient client = new DatadogClient(restTemplate, new ObjectMapper(), new RetryInformation(1, 0),
                mock(ExecutorService.class));

        assertThat(client.sendLogBatchWithRetries(new byte[] {'[', ']'}, "api-key", site)).isTrue();

        verify(restTemplate).exchange(eq("https://http-intake.logs." + site + "/api/v2/cilogs"), eq(POST),
                any(HttpEntity.class), eq(String.class));
    }
}
