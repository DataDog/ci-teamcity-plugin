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
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.BuildLog;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
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
        reporter = new JobLogReporter(datadogClient, objectMapper);
        timestamp = new Date();
        when(jobBuild.getBuildLog()).thenReturn(buildLog);
        when(datadogClient.sendLogBatchWithRetriesResult(any(byte[].class), eq("api-key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
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
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
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
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
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
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
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
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.get(0).get("section_name").asText()).isEqualTo("compile");
        assertThat(lines.get(1).get("section_name").asText()).isEqualTo("tests");
    }

    @Test
    public void doesNotSendAnEmptyBatch() {
        when(buildLog.getMessagesIterator()).thenReturn(Collections.<LogMessage>emptyList().iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        verify(datadogClient, never()).sendLogBatchWithRetriesResult(any(byte[].class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void doesNotSendABatchForOnlyBlankMessages() {
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(
                message("", Status.NORMAL, false), message("\n", Status.NORMAL, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        verify(datadogClient, never()).sendLogBatchWithRetriesResult(any(byte[].class), eq("api-key"), eq("datad0g.com"));
    }

    @Test
    public void omitsTimestampsOutsideTheIntakeWindow() throws IOException {
        LogMessage old = new LogMessage("old", Status.NORMAL, new Date(0), null, false, 0);
        LogMessage future = new LogMessage("future", Status.NORMAL,
                Date.from(Instant.now().plus(13, ChronoUnit.HOURS)), null, false, 0);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(old, future).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode lines = objectMapper.readTree(payload.getValue());
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).has("timestamp")).isFalse();
        assertThat(lines.get(1).has("timestamp")).isFalse();
    }

    @Test
    public void sendsALineWithoutATeamCityTimestamp() throws IOException {
        LogMessage message = mock(LogMessage.class);
        when(message.getText()).thenReturn("output");
        when(message.getStatus()).thenReturn(Status.NORMAL);
        when(buildLog.getMessagesIterator()).thenReturn(singletonList(message).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient).sendLogBatchWithRetriesResult(payload.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode line = objectMapper.readTree(payload.getValue()).get(0);
        assertThat(line.get("message").asText()).isEqualTo("output");
        assertThat(line.has("timestamp")).isFalse();
    }

    @Test
    public void batchesBySerializedUtf8Bytes() throws IOException {
        String escaped = repeat('\u0000', 350000);
        when(buildLog.getMessagesIterator()).thenReturn(Arrays.asList(
                message(escaped, Status.NORMAL, false), message(escaped, Status.NORMAL, false)).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.atLeast(2))
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
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
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
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
    public void resumesAfterTheLastAcceptedBatchWithoutSkippingLines() throws IOException {
        List<LogMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 1001; i++) {
            messages.add(message("line " + i, Status.NORMAL, false));
        }
        when(buildLog.getMessagesIterator()).thenAnswer(ignored -> messages.iterator());
        when(datadogClient.sendLogBatchWithRetriesResult(any(byte[].class), eq("api-key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS, DeliveryResult.RETRYABLE_FAILURE, DeliveryResult.SUCCESS);
        AtomicLong acknowledged = new AtomicLong();

        try {
            reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com", 0,
                    acknowledged::set);
            throw new AssertionError("Expected the second batch to fail");
        } catch (LogDeliveryException ex) {
            assertThat(ex.getResult()).isEqualTo(DeliveryResult.RETRYABLE_FAILURE);
        }
        assertThat(acknowledged.get()).isEqualTo(1000);

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com",
                acknowledged.get(), acknowledged::set);

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.times(3))
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        assertThat(objectMapper.readTree(payloads.getAllValues().get(0)).size()).isEqualTo(1000);
        assertThat(objectMapper.readTree(payloads.getAllValues().get(1)).size()).isEqualTo(1);
        JsonNode resumed = objectMapper.readTree(payloads.getAllValues().get(2));
        assertThat(resumed.size()).isEqualTo(1);
        assertThat(resumed.get(0).get("line_number").asLong()).isEqualTo(1001);
        assertThat(resumed.get(0).get("message").asText()).isEqualTo("line 1001");
        assertThat(acknowledged.get()).isEqualTo(1001);
    }

    @Test
    public void fragmentsKeepTheSameLineNumbersWhenTimestampIsOmitted() throws IOException {
        String text = repeat('x', LogBatch.MAX_FRAGMENT_CODEPOINTS * 2 + 7);
        LogMessage current = new LogMessage(text, Status.NORMAL, timestamp, null, false, 0);
        LogMessage old = new LogMessage(text, Status.NORMAL, new Date(0), null, false, 0);
        when(buildLog.getMessagesIterator()).thenReturn(
                singletonList(current).iterator(), singletonList(old).iterator());

        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");
        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.times(2))
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        JsonNode withTimestamp = objectMapper.readTree(payloads.getAllValues().get(0));
        JsonNode withoutTimestamp = objectMapper.readTree(payloads.getAllValues().get(1));
        assertThat(withTimestamp.size()).isEqualTo(3);
        assertThat(withoutTimestamp.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(withTimestamp.get(i).get("line_number").asInt()).isEqualTo(i + 1);
            assertThat(withoutTimestamp.get(i).get("line_number").asInt()).isEqualTo(i + 1);
            assertThat(withoutTimestamp.get(i).get("message").asText())
                    .isEqualTo(withTimestamp.get(i).get("message").asText());
        }
    }

    @Test
    public void resendsAnAcceptedBatchIfItsCheckpointWasNotSaved() throws IOException {
        when(buildLog.getMessagesIterator()).thenAnswer(ignored ->
                singletonList(message("output", Status.NORMAL, false)).iterator());

        try {
            reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com", 0,
                    ignored -> { throw new IllegalStateException("checkpoint unavailable"); });
            throw new AssertionError("Expected checkpoint persistence to fail");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).isEqualTo("checkpoint unavailable");
        }
        reporter.sendLogs(jobBuild, "pipeline-id", "job-id", "api-key", "datad0g.com", 0,
                ignored -> {});

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(datadogClient, org.mockito.Mockito.times(2))
                .sendLogBatchWithRetriesResult(payloads.capture(), eq("api-key"), eq("datad0g.com"));
        assertThat(objectMapper.readTree(payloads.getAllValues().get(0)).get(0).get("line_number").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(payloads.getAllValues().get(1)).get(0).get("line_number").asInt()).isEqualTo(1);
    }

    private LogMessage message(String text, Status status, boolean internal) {
        return new LogMessage(text, status, timestamp, null, false, 7,
                internal ? singletonList("tc:internal") : Collections.emptyList());
    }

    private String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }
}
