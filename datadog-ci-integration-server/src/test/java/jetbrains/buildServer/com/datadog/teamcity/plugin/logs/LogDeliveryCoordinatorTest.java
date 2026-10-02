/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LogDeliveryCoordinatorTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();
    private final DatadogClient client = mock(DatadogClient.class);
    private final JobLogReporter reporter = mock(JobLogReporter.class);
    private final BuildsManager buildsManager = mock(BuildsManager.class);
    private final ProjectHandler projectHandler = mock(ProjectHandler.class);
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    @After
    public void tearDown() {
        for (ScheduledExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 120000)
    public void deliversOneThousandJobsWithoutQueueRejection() throws Exception {
        LogDeliveryStore store = store();
        SBuild pipeline = mock(SBuild.class);
        when(pipeline.getBuildId()).thenReturn(2000L);
        when(buildsManager.findBuildInstanceById(anyLong())).thenReturn(pipeline);
        when(projectHandler.getProjectParameters(pipeline))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.sendWebhookPayloadWithRetries(anyString(), anyString(), eq("key"), eq("datad0g.com")))
                .thenAnswer(ignored -> {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(2);
                        return DeliveryResult.SUCCESS;
                    } finally {
                        active.decrementAndGet();
                    }
                });
        List<SBuild> jobs = new ArrayList<>();
        List<Webhook> webhooks = new ArrayList<>();
        webhooks.add(pipelineWebhook());
        for (int i = 1; i <= 1000; i++) {
            SBuild job = mock(SBuild.class);
            when(job.getBuildId()).thenReturn((long) i);
            jobs.add(job);
            webhooks.add(jobWebhook(i));
        }
        LogDeliveryCoordinator coordinator = coordinator(store);

        coordinator.enqueue(pipeline, jobs, webhooks, "datad0g.com");

        waitUntilComplete(2000);
        verify(reporter, times(1000)).sendLogs(any(SBuild.class), eq("pipeline-2000"), anyString(),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        verify(client, times(1001)).sendWebhookPayloadWithRetries(anyString(), anyString(),
                eq("key"), eq("datad0g.com"));
        assertThat(maximum.get()).isLessThanOrEqualTo(4);
    }

    @Test(timeout = 120000)
    public void drainsConcurrentPipelinesWithoutDroppingJobs() throws Exception {
        LogDeliveryStore store = store();
        SBuild build = mock(SBuild.class);
        when(buildsManager.findBuildInstanceById(anyLong())).thenReturn(build);
        when(projectHandler.getProjectParameters(build))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(client.sendWebhookPayloadWithRetries(anyString(), anyString(), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
        LogDeliveryCoordinator coordinator = coordinator(store);

        for (int pipelineNumber = 1; pipelineNumber <= 10; pipelineNumber++) {
            long pipelineBuildId = 2000L + pipelineNumber;
            SBuild pipeline = mock(SBuild.class);
            when(pipeline.getBuildId()).thenReturn(pipelineBuildId);
            List<SBuild> jobs = new ArrayList<>();
            List<Webhook> webhooks = new ArrayList<>();
            String pipelineId = "pipeline-" + pipelineBuildId;
            webhooks.add(new PipelineWebhook("pipeline", "url", "start", "end", pipelineId,
                    String.valueOf(pipelineBuildId), false, PipelineWebhook.PipelineStatus.SUCCESS));
            for (int jobNumber = 1; jobNumber <= 100; jobNumber++) {
                long jobBuildId = pipelineBuildId * 1000 + jobNumber;
                SBuild job = mock(SBuild.class);
                when(job.getBuildId()).thenReturn(jobBuildId);
                jobs.add(job);
                webhooks.add(new JobWebhook("job", "url", "start", "end", pipelineId, "pipeline",
                        "job-" + jobBuildId, SUCCESS, 0));
            }
            coordinator.enqueue(pipeline, jobs, webhooks, "datad0g.com");
        }

        for (int pipelineNumber = 1; pipelineNumber <= 10; pipelineNumber++) {
            waitUntilComplete(2000L + pipelineNumber);
        }
        verify(reporter, times(1000)).sendLogs(any(SBuild.class), anyString(), anyString(),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        verify(client, times(1010)).sendWebhookPayloadWithRetries(anyString(), anyString(),
                eq("key"), eq("datad0g.com"));
    }

    @Test(timeout = 15000)
    public void resumesAnExhaustedJobAfterRestartWithoutSendingItsWebhookEarly() throws Exception {
        LogDeliveryStore store = store();
        SBuild pipeline = mock(SBuild.class);
        SBuild job = mock(SBuild.class);
        when(pipeline.getBuildId()).thenReturn(2000L);
        when(job.getBuildId()).thenReturn(1L);
        when(buildsManager.findBuildInstanceById(2000L)).thenReturn(pipeline);
        when(buildsManager.findBuildInstanceById(1L)).thenReturn(job);
        when(projectHandler.getProjectParameters(pipeline))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(client.sendWebhookPayloadWithRetries(anyString(), anyString(), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
        doAnswer(invocation -> {
            ((LongConsumer) invocation.getArguments()[6]).accept(1000);
            throw new LogDeliveryException(DeliveryResult.RETRYABLE_FAILURE, "temporary");
        }).when(reporter).sendLogs(eq(job), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        List<SBuild> jobs = java.util.Collections.singletonList(job);
        List<Webhook> webhooks = new ArrayList<>();
        webhooks.add(pipelineWebhook());
        webhooks.add(jobWebhook(1));
        LogDeliveryCoordinator first = coordinator(store);

        first.enqueue(pipeline, jobs, webhooks, "datad0g.com");
        waitUntilFailed(store, 2000, 1L);
        assertThat(store.progress(2000, 1L).lastAcknowledgedLine).isEqualTo(1000);
        verify(client, times(0)).sendWebhookPayloadWithRetries(anyString(), eq("job-1"),
                eq("key"), eq("datad0g.com"));

        executors.get(0).shutdownNow();
        DeliveryProgress progress = store.progress(2000, 1L);
        progress.nextAttemptAtMillis = 0;
        store.saveProgress(2000, 1L, progress);
        LogDeliveryCoordinator recovered = coordinator(store);
        recovered.afterPropertiesSet();

        waitUntilComplete(2000);
        verify(reporter, times(1)).sendLogs(eq(job), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        verify(reporter, times(1)).sendLogs(eq(job), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(1000L), any(LongConsumer.class));
        verify(client, times(1)).sendWebhookPayloadWithRetries(anyString(), eq("job-1"),
                eq("key"), eq("datad0g.com"));
    }

    @Test(timeout = 10000)
    public void keepsRejectedLogsBlockedWithoutSendingTheJobWebhook() throws Exception {
        LogDeliveryStore store = store();
        SBuild pipeline = mock(SBuild.class);
        SBuild job = mock(SBuild.class);
        when(pipeline.getBuildId()).thenReturn(2000L);
        when(job.getBuildId()).thenReturn(1L);
        when(buildsManager.findBuildInstanceById(2000L)).thenReturn(pipeline);
        when(buildsManager.findBuildInstanceById(1L)).thenReturn(job);
        when(projectHandler.getProjectParameters(pipeline))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(client.sendWebhookPayloadWithRetries(anyString(), anyString(), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
        doAnswer(ignored -> {
            throw new LogDeliveryException(DeliveryResult.BLOCKED, "invalid key");
        }).when(reporter).sendLogs(eq(job), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        List<Webhook> webhooks = new ArrayList<>();
        webhooks.add(pipelineWebhook());
        webhooks.add(jobWebhook(1));

        coordinator(store).enqueue(pipeline, java.util.Collections.singletonList(job), webhooks, "datad0g.com");

        waitUntilFailed(store, 2000, 1L);
        DeliveryProgress progress = store.progress(2000, 1L);
        assertThat(progress.blocked).isTrue();
        assertThat(progress.nextAttemptAtMillis).isGreaterThan(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(59));
        verify(client, times(0)).sendWebhookPayloadWithRetries(anyString(), eq("job-1"),
                eq("key"), eq("datad0g.com"));
    }

    @Test(timeout = 10000)
    public void duplicateCompositeCallbackDoesNotCreateAnotherDelivery() throws Exception {
        LogDeliveryStore store = store();
        SBuild pipeline = mock(SBuild.class);
        SBuild job = mock(SBuild.class);
        when(pipeline.getBuildId()).thenReturn(2000L);
        when(job.getBuildId()).thenReturn(1L);
        when(buildsManager.findBuildInstanceById(anyLong())).thenReturn(pipeline);
        when(projectHandler.getProjectParameters(pipeline))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(client.sendWebhookPayloadWithRetries(anyString(), anyString(), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
        List<SBuild> jobs = java.util.Collections.singletonList(job);
        List<Webhook> webhooks = new ArrayList<>();
        webhooks.add(pipelineWebhook());
        webhooks.add(jobWebhook(1));
        LogDeliveryCoordinator coordinator = coordinator(store);

        coordinator.enqueue(pipeline, jobs, webhooks, "datad0g.com");
        coordinator.enqueue(pipeline, jobs, webhooks, "datad0g.com");
        waitUntilComplete(2000);
        coordinator.enqueue(pipeline, jobs, webhooks, "datad0g.com");

        verify(reporter, times(1)).sendLogs(any(SBuild.class), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
        verify(client, times(1)).sendWebhookPayloadWithRetries(anyString(), eq("job-1"),
                eq("key"), eq("datad0g.com"));
    }

    @Test(timeout = 15000)
    public void doesNotStartJobsUntilThePipelineWebhookRecovers() throws Exception {
        LogDeliveryStore store = store();
        SBuild pipeline = mock(SBuild.class);
        SBuild job = mock(SBuild.class);
        when(pipeline.getBuildId()).thenReturn(2000L);
        when(job.getBuildId()).thenReturn(1L);
        when(buildsManager.findBuildInstanceById(2000L)).thenReturn(pipeline);
        when(buildsManager.findBuildInstanceById(1L)).thenReturn(job);
        when(projectHandler.getProjectParameters(pipeline))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(client.sendWebhookPayloadWithRetries(anyString(), eq("pipeline-2000"), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.RETRYABLE_FAILURE, DeliveryResult.SUCCESS);
        when(client.sendWebhookPayloadWithRetries(anyString(), eq("job-1"), eq("key"), eq("datad0g.com")))
                .thenReturn(DeliveryResult.SUCCESS);
        List<Webhook> webhooks = new ArrayList<>();
        webhooks.add(pipelineWebhook());
        webhooks.add(jobWebhook(1));
        coordinator(store).enqueue(pipeline, java.util.Collections.singletonList(job), webhooks, "datad0g.com");

        waitUntilFailed(store, 2000, null);
        verify(reporter, times(0)).sendLogs(any(SBuild.class), anyString(), anyString(),
                anyString(), anyString(), anyLong(), any(LongConsumer.class));

        executors.get(0).shutdownNow();
        DeliveryProgress progress = store.progress(2000, null);
        progress.nextAttemptAtMillis = 0;
        store.saveProgress(2000, null, progress);
        LogDeliveryCoordinator recovered = coordinator(store);
        recovered.afterPropertiesSet();

        waitUntilComplete(2000);
        verify(reporter, times(1)).sendLogs(eq(job), eq("pipeline-2000"), eq("job-1"),
                eq("key"), eq("datad0g.com"), eq(0L), any(LongConsumer.class));
    }

    private LogDeliveryStore store() {
        return new LogDeliveryStore(temporaryFolder.getRoot().toPath(), mapper);
    }

    private LogDeliveryCoordinator coordinator(LogDeliveryStore store) {
        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(4);
        executors.add(executor);
        return new LogDeliveryCoordinator(store, reporter, client, buildsManager, projectHandler, mapper, executor);
    }

    private PipelineWebhook pipelineWebhook() {
        return new PipelineWebhook("pipeline", "url", "start", "end", "pipeline-2000", "2000", false,
                PipelineWebhook.PipelineStatus.SUCCESS);
    }

    private JobWebhook jobWebhook(int buildId) {
        return new JobWebhook("job", "url", "start", "end", "pipeline-2000", "pipeline",
                "job-" + buildId, SUCCESS, 0);
    }

    private void waitUntilComplete(long pipelineBuildId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(90);
        while (!Files.exists(temporaryFolder.getRoot().toPath().resolve("pipeline-" + pipelineBuildId + ".done"))) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Delivery did not complete");
            }
            Thread.sleep(20);
        }
    }

    private void waitUntilFailed(LogDeliveryStore store, long pipelineBuildId, Long jobBuildId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (store.progress(pipelineBuildId, jobBuildId).failedCycles == 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Delivery did not record a retry");
            }
            Thread.sleep(10);
        }
    }
}
