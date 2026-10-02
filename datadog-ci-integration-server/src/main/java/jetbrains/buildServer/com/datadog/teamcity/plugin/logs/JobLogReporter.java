/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.function.LongConsumer;

@Component
public class JobLogReporter {

    private final DatadogClient datadogClient;
    private final ObjectMapper objectMapper;

    public JobLogReporter(DatadogClient datadogClient, ObjectMapper objectMapper) {
        this.datadogClient = datadogClient;
        this.objectMapper = objectMapper;
    }

    void sendLogs(SBuild jobBuild, String pipelineID, String jobID, String apiKey, String ddSite) {
        sendLogs(jobBuild, pipelineID, jobID, apiKey, ddSite, 0, ignored -> {});
    }

    public void sendLogs(SBuild jobBuild, String pipelineID, String jobID, String apiKey, String ddSite,
                         long lastAcknowledgedLine, LongConsumer onBatchAccepted) {
        LogBatch batch = new LogBatch(datadogClient, objectMapper, pipelineID, jobID, apiKey, ddSite,
                lastAcknowledgedLine, onBatchAccepted);
        Iterator<LogMessage> messages = jobBuild.getBuildLog().getMessagesIterator();
        while (messages.hasNext()) {
            LogMessage message = messages.next();
            if (message instanceof BlockLogMessage || message.isInternal()) {
                continue;
            }

            String[] lines = message.getText().split("\\r\\n|\\r|\\n", -1);
            int lineCount = lines.length;
            if (lineCount > 1 && lines[lineCount - 1].isEmpty()) {
                lineCount--;
            }
            for (int i = 0; i < lineCount; i++) {
                batch.addLine(message, lines[i]);
            }
        }
        batch.flush();
    }
}
