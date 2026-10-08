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
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.BuildLog;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static java.util.Collections.singletonList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class JobLogReporterTest {

    @Mock private DatadogClient datadogClient;
    @Mock private SBuild jobBuild;
    @Mock private BuildLog buildLog;

    private ObjectMapper objectMapper;
    private JobLogReporter reporter;
    private Date timestamp;

    @Before
    public void setUp() {
        objectMapper = new DatadogConfiguration().objectMapper();
        reporter = new JobLogReporter(datadogClient, objectMapper, Runnable::run);
        timestamp = new Date();
        when(jobBuild.getBuildLog()).thenReturn(buildLog);
        when(datadogClient.sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com")))
                .thenReturn(true);
    }

    @Test
    public void sendsVisibleLinesWithTeamCityContext() throws IOException {
        BlockLogMessage step = new BlockLogMessage("Step 1", "step", new Date(0), null, 7);
        BlockLogMessage block = new BlockLogMessage("unit tests", "block", new Date(0), null, 7);
        block.setParent(step);
        LogMessage hidden = message("internal", Status.NORMAL, true);
        LogMessage output = message("first\r\nsecond\n", Status.WARNING, false);
        output.setParent(block);
        output.setIndex(42);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(step, block, hidden, output).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetries(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).get("message").asText()).isEqualTo("first");
        assertThat(lines.get(1).get("message").asText()).isEqualTo("second");
        assertThat(lines.get(0).get("line_number").asInt()).isEqualTo(1);
        assertThat(lines.get(1).get("line_number").asInt()).isEqualTo(2);
        assertThat(lines.get(0).get("pipeline_unique_id").asText()).isEqualTo("pipeline-id");
        assertThat(lines.get(0).get("job_id").asText()).isEqualTo("job-id");
        assertThat(lines.get(0).get("provider_name").asText()).isEqualTo("teamcity");
        assertThat(lines.get(0).get("timestamp").asText())
                .isEqualTo(DateTimeFormatter.ISO_INSTANT.format(timestamp.toInstant()));
        assertThat(lines.get(0).get("status").asText()).isEqualTo("warn");
        assertThat(lines.get(0).get("teamcity.flow_id").asInt()).isEqualTo(7);
        assertThat(lines.get(0).get("teamcity.message_index").asInt()).isEqualTo(42);
        assertThat(lines.get(0).get("section_name").asText()).isEqualTo("unit tests");
    }

    @Test
    public void skipsBlankLinesAndKeepsLineNumbersContiguous() throws IOException {
        LogMessage output = new LogMessage("first\n\nlast", Status.NORMAL, timestamp, null, false, 0);
        when(buildLog.getMessagesIterator()).thenReturn(singletonList(output).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetries(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).get("message").asText()).isEqualTo("first");
        assertThat(lines.get(1).get("message").asText()).isEqualTo("last");
        assertThat(lines.get(0).get("line_number").asInt()).isEqualTo(1);
        assertThat(lines.get(1).get("line_number").asInt()).isEqualTo(2);
        assertThat(lines.get(0).has("section_name")).isFalse();
    }

    @Test
    public void mapsTeamCityMessageStatusesToLogStatus() throws IOException {
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(
                message("normal", Status.NORMAL, false),
                message("warning", Status.WARNING, false),
                message("failure", Status.FAILURE, false),
                message("error", Status.ERROR, false),
                message("unknown", Status.UNKNOWN, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetries(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.size()).isEqualTo(5);
        assertThat(lines.get(0).get("status").asText()).isEqualTo("info");
        assertThat(lines.get(1).get("status").asText()).isEqualTo("warn");
        assertThat(lines.get(2).get("status").asText()).isEqualTo("error");
        assertThat(lines.get(3).get("status").asText()).isEqualTo("error");
        assertThat(lines.get(4).has("status")).isFalse();
    }

    @Test
    public void usesInnermostBlockForEachLine() throws IOException {
        BlockLogMessage outer = new BlockLogMessage("build", "block", timestamp, null, 0);
        BlockLogMessage compile = new BlockLogMessage("compile", "block", timestamp, null, 0);
        compile.setParent(outer);
        BlockLogMessage tests = new BlockLogMessage("tests", "block", timestamp, null, 0);
        tests.setParent(outer);
        LogMessage first = message("compiling", Status.NORMAL, false);
        first.setParent(compile);
        LogMessage second = message("testing", Status.NORMAL, false);
        second.setParent(tests);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(first, second).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetries(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.get(0).get("section_name").asText()).isEqualTo("compile");
        assertThat(lines.get(1).get("section_name").asText()).isEqualTo("tests");
    }

    @Test
    public void doesNotSendAnEmptyBatch() {
        when(buildLog.getMessagesIterator()).thenReturn(Collections.<LogMessage>emptyList().iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        verify(datadogClient, never()).sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void doesNotSendABatchForOnlyBlankMessages() {
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(
                message("", Status.NORMAL, false), message("\n", Status.NORMAL, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        verify(datadogClient, never()).sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void omitsTimestampsOutsideTheIntakeWindow() throws IOException {
        LogMessage old = new LogMessage("old", Status.NORMAL, new Date(0), null, false, 0);
        LogMessage future = new LogMessage("future", Status.NORMAL,
                Date.from(Instant.now().plus(13, ChronoUnit.HOURS)), null, false, 0);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(old, future).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetries(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).has("timestamp")).isFalse();
        assertThat(lines.get(1).has("timestamp")).isFalse();
    }

    @Test
    public void batchesBySerializedUtf8Bytes() throws IOException {
        String escaped = repeat('\u0000', 350000);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(
                message(escaped, Status.NORMAL, false), message(escaped, Status.NORMAL, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.atLeast(2))
                .sendLogBatchWithRetries(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        StringBuilder reconstructed = new StringBuilder();
        int nextLineNumber = 1;
        for (byte[] payload : payloads.getAllValues()) {
            assertThat(payload.length).isLessThanOrEqualTo(LogBatch.MAX_BATCH_BYTES);
            for (JsonNode line : objectMapper.readTree(payload)) {
                assertThat(objectMapper.writeValueAsBytes(line).length).isLessThanOrEqualTo(LogBatch.MAX_LOG_BYTES);
                reconstructed.append(line.get("message").asText());
                assertThat(line.get("line_number").asInt()).isEqualTo(nextLineNumber++);
            }
        }
        assertThat(reconstructed.toString()).isEqualTo(escaped + escaped);
        assertThat(nextLineNumber).isGreaterThan(4);
    }

    @Test
    public void splitsAnOversizedLineWithoutLosingUnicode() throws IOException {
        String original = repeat('x', LogBatch.MAX_BATCH_BYTES) + "🔥tail";
        when(buildLog.getMessagesIterator()).thenReturn(singletonList(message(original, Status.NORMAL, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.atLeast(2))
                .sendLogBatchWithRetries(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        StringBuilder reconstructed = new StringBuilder();
        int nextLineNumber = 1;
        for (byte[] payload : payloads.getAllValues()) {
            assertThat(payload.length).isLessThanOrEqualTo(LogBatch.MAX_BATCH_BYTES);
            JsonNode batch = objectMapper.readTree(payload);
            for (JsonNode line : batch) {
                assertThat(objectMapper.writeValueAsBytes(line).length).isLessThanOrEqualTo(LogBatch.MAX_LOG_BYTES);
                reconstructed.append(line.get("message").asText());
                assertThat(line.get("line_number").asInt()).isEqualTo(nextLineNumber++);
                assertThat(line.has("teamcity.fragment_index")).isFalse();
            }
        }
        assertThat(reconstructed.toString()).isEqualTo(original);
        assertThat(nextLineNumber).isGreaterThan(2);
    }

    @Test
    public void sendsLogsBeforeTheJobWebhook() {
        when(buildLog.getMessagesIterator()).thenReturn(singletonList(message("output", Status.NORMAL, false)).iterator());
        JobWebhook webhook = webhook();

        reporter.sendJobWithLogsAsync(jobBuild, webhook, "pipeline-id", "api-key", "datad0g.com");

        InOrder order = inOrder(datadogClient);
        order.verify(datadogClient).sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com"));
        order.verify(datadogClient).sendWebhookWithRetries(webhook, "api-key", "datad0g.com");
    }

    @Test
    public void sendsTheJobWebhookWhenLogDeliveryFails() {
        when(buildLog.getMessagesIterator()).thenReturn(singletonList(message("output", Status.NORMAL, false)).iterator());
        when(datadogClient.sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com")))
                .thenReturn(false);

        reporter.sendJobWithLogsAsync(jobBuild, webhook(), "pipeline-id", "api-key", "datad0g.com");

        verify(datadogClient).sendWebhookWithRetries(any(JobWebhook.class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void sendsTheJobWebhookWhenLogReadingFails() {
        when(jobBuild.getBuildLog()).thenThrow(new IllegalStateException("unavailable"));

        reporter.sendJobWithLogsAsync(jobBuild, webhook(), "pipeline-id", "api-key", "datad0g.com");

        verify(datadogClient).sendWebhookWithRetries(any(JobWebhook.class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void skipsLogsWhenTheExecutorIsFull() {
        Executor rejectingExecutor = task -> { throw new RejectedExecutionException(); };
        reporter = new JobLogReporter(datadogClient, objectMapper, rejectingExecutor);
        JobWebhook webhook = webhook();

        reporter.sendJobWithLogsAsync(jobBuild, webhook, "pipeline-id", "api-key", "datad0g.com");

        verify(datadogClient).sendWebhooksAsync(singletonList(webhook), "api-key", "datad0g.com");
        verify(datadogClient, never()).sendLogBatchWithRetries(any(byte[].class), eq("api-key"), eq("datad0g.com"));
    }

    private LogMessage message(String text, Status status, boolean internal) {
        return new LogMessage(text, status, timestamp, null, false, 7,
                internal ? singletonList("tc:internal") : Collections.emptyList());
    }

    private JobWebhook webhook() {
        return new JobWebhook("job", "url", "start", "end", "pipeline-id", "pipeline", "job-id", SUCCESS, 0);
    }

    private String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }
}
