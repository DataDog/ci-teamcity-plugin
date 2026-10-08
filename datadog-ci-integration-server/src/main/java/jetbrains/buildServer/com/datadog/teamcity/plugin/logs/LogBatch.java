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
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

final class LogBatch {

    static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    static final int MAX_BATCH_LINES = 1000;
    static final int MAX_LOG_BYTES = 900 * 1024;

    private final DatadogClient datadogClient;
    private final ObjectMapper objectMapper;
    private final String pipelineID;
    private final String jobID;
    private final String apiKey;
    private final String ddSite;
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

    private int count;
    private long nextLineNumber = 1;

    LogBatch(DatadogClient datadogClient, ObjectMapper objectMapper, String pipelineID, String jobID, String apiKey, String ddSite) {
        this.datadogClient = datadogClient;
        this.objectMapper = objectMapper;
        this.pipelineID = pipelineID;
        this.jobID = jobID;
        this.apiKey = apiKey;
        this.ddSite = ddSite;
        payload.write('[');
    }

    void addLine(LogMessage source, String text) {
        if (text.isEmpty()) {
            return;
        }
        Map<String, Object> line = createLine(source, text);
        byte[] serialized = serialize(line);
        if (serialized.length <= MAX_LOG_BYTES) {
            append(serialized);
            nextLineNumber++;
            return;
        }
        int offset = 0;
        while (offset < text.length()) {
            line.put("line_number", nextLineNumber);
            int end = largestFittingEnd(text, offset, line);
            if (end == offset) {
                throw new IllegalArgumentException("A CI log line's metadata exceeds the record byte limit");
            }
            line.put("message", text.substring(offset, end));
            append(serialize(line));
            nextLineNumber++;
            offset = end;
        }
    }

    private int largestFittingEnd(String text, int offset, Map<String, Object> line) {
        int remaining = text.codePointCount(offset, text.length());
        int low = 1;
        int high = remaining;
        int best = offset;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int end = text.offsetByCodePoints(offset, middle);
            line.put("message", text.substring(offset, end));
            if (serialize(line).length <= MAX_LOG_BYTES) {
                best = end;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private Map<String, Object> createLine(LogMessage source, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("message", text);
        line.put("pipeline_unique_id", pipelineID);
        line.put("job_id", jobID);
        line.put("provider_name", "teamcity");
        line.put("line_number", nextLineNumber);
        Instant timestamp = source.getTimestamp().toInstant();
        Instant now = Instant.now();
        if (!timestamp.isBefore(now.minus(18, ChronoUnit.HOURS)) && !timestamp.isAfter(now.plus(12, ChronoUnit.HOURS))) {
            line.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
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
        if (!datadogClient.sendLogBatchWithRetries(payload.toByteArray(), apiKey, ddSite)) {
            throw new IllegalStateException(format("Could not send a CI log batch for job '%s'", jobID));
        }
        payload.reset();
        payload.write('[');
        count = 0;
    }
}
