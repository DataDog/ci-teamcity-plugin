/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static java.lang.String.format;
import static java.util.Collections.singletonList;

@Component
public class JobLogReporter {

    private static final Logger LOG = Logger.getInstance(JobLogReporter.class.getName());

    private final DatadogClient datadogClient;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public JobLogReporter(DatadogClient datadogClient, ObjectMapper objectMapper,
                          @Qualifier("logReportingExecutor") Executor executor) {
        this.datadogClient = datadogClient;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public void sendJobWithLogsAsync(SBuild jobBuild, JobWebhook webhook, String pipelineID, String apiKey, String ddSite) {
        try {
            executor.execute(() -> {
                try {
                    sendLogs(jobBuild, pipelineID, webhook.id(), apiKey, ddSite);
                } catch (RuntimeException ex) {
                    LOG.warn(format("Could not finish sending logs for job '%s'", webhook.id()), ex);
                }
                datadogClient.sendWebhookWithRetries(webhook, apiKey, ddSite);
            });
        } catch (RejectedExecutionException ex) {
            LOG.warn(format("Could not queue logs for job '%s'", webhook.id()), ex);
            datadogClient.sendWebhooksAsync(singletonList(webhook), apiKey, ddSite);
        }
    }

    void sendLogs(SBuild jobBuild, String pipelineID, String jobID, String apiKey, String ddSite) {
        LogBatch batch = new LogBatch(datadogClient, objectMapper, pipelineID, jobID, apiKey, ddSite);
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
