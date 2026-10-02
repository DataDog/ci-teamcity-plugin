/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;

import static java.lang.String.format;

final class LogBatch {

    static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    static final int MAX_BATCH_LINES = 1000;
    static final int MAX_LOG_BYTES = 900 * 1024;
    static final int MAX_FRAGMENT_CODEPOINTS = 32768;

    private final DatadogClient datadogClient;
    private final ObjectMapper objectMapper;
    private final String pipelineID;
    private final String jobID;
    private final String apiKey;
    private final String ddSite;
    private final long lastAcknowledgedLine;
    private final LongConsumer onBatchAccepted;
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

    private int count;
    private long nextLineNumber = 1;

    LogBatch(DatadogClient datadogClient, ObjectMapper objectMapper, String pipelineID, String jobID, String apiKey, String ddSite) {
        this(datadogClient, objectMapper, pipelineID, jobID, apiKey, ddSite, 0, ignored -> {});
    }

    LogBatch(DatadogClient datadogClient, ObjectMapper objectMapper, String pipelineID, String jobID,
             String apiKey, String ddSite, long lastAcknowledgedLine, LongConsumer onBatchAccepted) {
        this.datadogClient = datadogClient;
        this.objectMapper = objectMapper;
        this.pipelineID = pipelineID;
        this.jobID = jobID;
        this.apiKey = apiKey;
        this.ddSite = ddSite;
        this.lastAcknowledgedLine = lastAcknowledgedLine;
        this.onBatchAccepted = onBatchAccepted;
        payload.write('[');
    }

    void addLine(LogMessage source, String text) {
        if (text.isEmpty()) {
            return;
        }
        int offset = 0;
        int remainingCodepoints = text.codePointCount(0, text.length());
        while (remainingCodepoints > 0) {
            int fragmentCodepoints = Math.min(MAX_FRAGMENT_CODEPOINTS, remainingCodepoints);
            int end = text.offsetByCodePoints(offset, fragmentCodepoints);
            byte[] serialized = serialize(createLine(source, text.substring(offset, end)));
            if (serialized.length > MAX_LOG_BYTES) {
                throw new IllegalArgumentException("A CI log line's metadata exceeds the record byte limit");
            }
            append(serialized);
            nextLineNumber++;
            offset = end;
            remainingCodepoints -= fragmentCodepoints;
        }
    }

    private Map<String, Object> createLine(LogMessage source, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("message", text);
        line.put("pipeline_unique_id", pipelineID);
        line.put("job_id", jobID);
        line.put("provider_name", "teamcity");
        line.put("line_number", nextLineNumber);
        Date sourceTimestamp = source.getTimestamp();
        if (sourceTimestamp != null) {
            Instant timestamp = sourceTimestamp.toInstant();
            Instant now = Instant.now();
            if (!timestamp.isBefore(now.minus(18, ChronoUnit.HOURS)) && !timestamp.isAfter(now.plus(12, ChronoUnit.HOURS))) {
                line.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
            }
        }
        Status status = source.getStatus();
        if (status == Status.NORMAL) {
            line.put("status", "info");
        } else if (status == Status.WARNING) {
            line.put("status", "warn");
        } else if (status == Status.FAILURE || status == Status.ERROR) {
            line.put("status", "error");
        }
        line.put("teamcity.flow_id", source.getFlowId());
        if (source.getIndex() >= 0) {
            line.put("teamcity.message_index", source.getIndex());
        }

        BlockLogMessage parent = source.getParent();
        if (parent != null && !parent.getText().isEmpty()) {
            line.put("section_name", parent.getText());
        }
        return line;
    }

    private byte[] serialize(Map<String, Object> line) {
        try {
            return objectMapper.writeValueAsBytes(line);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize a CI log line", ex);
        }
    }

    private void append(byte[] line) {
        if (nextLineNumber <= lastAcknowledgedLine) {
            return;
        }
        if (count == MAX_BATCH_LINES || payload.size() + (count == 0 ? 0 : 1) + line.length + 1 > MAX_BATCH_BYTES) {
            flush();
        }
        if (count > 0) {
            payload.write(',');
        }
        payload.write(line, 0, line.length);
        count++;
    }

    void flush() {
        if (count == 0) {
            return;
        }
        payload.write(']');
        DeliveryResult result = datadogClient.sendLogBatchWithRetriesResult(payload.toByteArray(), apiKey, ddSite);
        if (result != DeliveryResult.SUCCESS) {
            throw new LogDeliveryException(result, format("Could not send a CI log batch for job '%s'", jobID));
        }
        onBatchAccepted.accept(nextLineNumber - 1);
        payload.reset();
        payload.write('[');
        count = 0;
    }
}
