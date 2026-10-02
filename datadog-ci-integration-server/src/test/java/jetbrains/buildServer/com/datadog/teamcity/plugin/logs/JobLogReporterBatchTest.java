/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogConfiguration;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.buildLog.BuildLog;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class JobLogReporterBatchTest {

    @Parameterized.Parameters(name = "{0} log lines")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
                {0, new int[] {}},
                {1, new int[] {1}},
                {999, new int[] {999}},
                {1000, new int[] {1000}},
                {1001, new int[] {1000, 1}},
                {2001, new int[] {1000, 1000, 1}}
        });
    }

    private final int lineCount;
    private final int[] expectedBatchCounts;

    public JobLogReporterBatchTest(int lineCount, int[] expectedBatchCounts) {
        this.lineCount = lineCount;
        this.expectedBatchCounts = expectedBatchCounts;
    }

    @Test
    public void keepsEveryBatchWithinTheLineAndByteLimits() throws IOException {
        DatadogClient client = mock(DatadogClient.class);
        ObjectMapper mapper = new DatadogConfiguration().objectMapper();
        SBuild build = mock(SBuild.class);
        BuildLog log = mock(BuildLog.class);
        List<LogMessage> messages = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            messages.add(new LogMessage("line " + i, Status.NORMAL, new Date(0), null, false, 0));
        }
        when(build.getBuildLog()).thenReturn(log);
        when(log.getMessagesIterator()).thenReturn(messages.iterator());
        when(client.sendLogBatchWithRetriesResult(any(byte[].class), eq("api-key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);

        new JobLogReporter(client, mapper)
                .sendLogs(build, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(expectedBatchCounts.length))
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        long lineNumber = 1;
        for (int i = 0; i < expectedBatchCounts.length; i++) {
            byte[] payload = payloads.getAllValues().get(i);
            JsonNode batch = mapper.readTree(payload);
            assertThat(payload.length).isLessThanOrEqualTo(LogBatch.MAX_BATCH_BYTES);
            assertThat(batch.size()).isEqualTo(expectedBatchCounts[i]);
            assertThat(batch.size()).isLessThanOrEqualTo(LogBatch.MAX_BATCH_LINES);
            for (JsonNode line : batch) {
                assertThat(mapper.writeValueAsBytes(line).length).isLessThanOrEqualTo(LogBatch.MAX_LOG_BYTES);
                assertThat(line.get("line_number").asLong()).isEqualTo(lineNumber++);
            }
        }
        assertThat(lineNumber).isEqualTo(lineCount + 1L);
    }
}
