/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LogDeliveryCoordinator implements InitializingBean {
    private static final Logger LOG = Logger.getInstance(LogDeliveryCoordinator.class.getName());
    private static final long RETRY_BASE_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long RETRY_MAX_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long BLOCKED_RETRY_MS = TimeUnit.HOURS.toMillis(1);

    private final LogDeliveryStore store;
    private final JobLogReporter reporter;
    private final DatadogClient client;
    private final BuildsManager buildsManager;
    private final ProjectHandler projectHandler;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService executor;
    private final Map<Long, AtomicInteger> remainingJobs = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastWarnings = new ConcurrentHashMap<>();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    public LogDeliveryCoordinator(LogDeliveryStore store, JobLogReporter reporter, DatadogClient client,
                                  BuildsManager buildsManager, ProjectHandler projectHandler, ObjectMapper mapper,
                                  @Qualifier("logReportingExecutor") ScheduledExecutorService executor) {
        this.store = store;
        this.reporter = reporter;
        this.client = client;
        this.buildsManager = buildsManager;
        this.projectHandler = projectHandler;
        this.mapper = mapper;
        this.executor = executor;
    }

    @Override
    public void afterPropertiesSet() {
        store.recoveryStartMillis();
        recoverPending();
        executor.scheduleWithFixedDelay(this::recoverPending, 1, 1, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(() -> store.purgeCompleted(Duration.ofDays(31)), 1, 1, TimeUnit.DAYS);
    }

    public boolean isKnown(long pipelineBuildId) {
        return store.contains(pipelineBuildId);
    }

    public long recoveryStartMillis() {
        return store.recoveryStartMillis();
    }

    public void markRecovered(long scanStartedAtMillis) {
        store.markRecovered(scanStartedAtMillis);
    }

    public void scheduleRecovery(Runnable recovery) {
        executor.scheduleWithFixedDelay(() -> {
            try {
                recovery.run();
            } catch (RuntimeException ex) {
                LOG.error("Could not reconcile recent TeamCity builds for CI log delivery", ex);
            }
        }, 30, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    public void enqueue(SBuild pipelineBuild, List<SBuild> jobBuilds, List<Webhook> webhooks, String ddSite) {
        List<DeliveryJob> jobs = new ArrayList<>();
        try {
            for (int i = 0; i < jobBuilds.size(); i++) {
                JobWebhook webhook = (JobWebhook) webhooks.get(i + 1);
                jobs.add(new DeliveryJob(jobBuilds.get(i).getBuildId(), webhook.id(), mapper.writeValueAsString(webhook)));
            }
            DeliveryManifest manifest = new DeliveryManifest(pipelineBuild.getBuildId(), webhooks.get(0).id(),
                    ddSite, mapper.writeValueAsString(webhooks.get(0)), jobs);
            if (store.create(manifest)) {
                schedulePipeline(manifest, 0);
            } else {
                recoverPending();
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize CI log delivery for build " + pipelineBuild.getBuildId(), ex);
        }
    }

    public void recoverPending() {
        try {
            for (DeliveryManifest manifest : store.list()) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - manifest.createdAtMillis > TimeUnit.MINUTES.toMillis(15)) {
                        Long lastWarning = lastWarnings.get(manifest.pipelineBuildId);
                        if (lastWarning == null || now - lastWarning > TimeUnit.MINUTES.toMillis(15)) {
                            lastWarnings.put(manifest.pipelineBuildId, now);
                            LOG.warn("CI log delivery remains pending for TeamCity pipeline build " + manifest.pipelineBuildId);
                        }
                    }
                    DeliveryProgress pipeline = store.progress(manifest.pipelineBuildId, null);
                    if (!pipeline.webhookSent) {
                        schedulePipeline(manifest, pipeline.nextAttemptAtMillis);
                    } else {
                        scheduleJobs(manifest);
                    }
                } catch (RuntimeException ex) {
                    LOG.error("Could not recover CI log delivery for build " + manifest.pipelineBuildId, ex);
                }
            }
        } catch (RuntimeException ex) {
            LOG.error("Could not recover pending CI log deliveries", ex);
        }
    }

    private void scheduleJobs(DeliveryManifest manifest) {
        List<DeliveryJob> pendingJobs = new ArrayList<>();
        for (DeliveryJob job : manifest.jobs) {
            DeliveryProgress progress = store.progress(manifest.pipelineBuildId, job.buildId);
            if (!progress.webhookSent) {
                pendingJobs.add(job);
            }
        }
        if (pendingJobs.isEmpty()) {
            store.complete(manifest);
            remainingJobs.remove(manifest.pipelineBuildId);
            lastWarnings.remove(manifest.pipelineBuildId);
            return;
        }
        remainingJobs.computeIfAbsent(manifest.pipelineBuildId,
                ignored -> new AtomicInteger(pendingJobs.size()));
        for (DeliveryJob job : pendingJobs) {
            DeliveryProgress progress = store.progress(manifest.pipelineBuildId, job.buildId);
            scheduleJob(manifest, job, progress.nextAttemptAtMillis);
        }
    }

    private void schedulePipeline(DeliveryManifest manifest, long dueAtMillis) {
        String key = "pipeline:" + manifest.pipelineBuildId;
        if (scheduled.add(key)) {
            schedule(() -> deliverPipeline(manifest, key), dueAtMillis);
        }
    }

    private void scheduleJob(DeliveryManifest manifest, DeliveryJob job, long dueAtMillis) {
        String key = "job:" + manifest.pipelineBuildId + ":" + job.buildId;
        if (scheduled.add(key)) {
            schedule(() -> deliverJob(manifest, job, key), dueAtMillis);
        }
    }

    private void schedule(Runnable task, long dueAtMillis) {
        executor.schedule(task, Math.max(0, dueAtMillis - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    }

    private void deliverPipeline(DeliveryManifest manifest, String key) {
        if (store.isComplete(manifest.pipelineBuildId)) {
            scheduled.remove(key);
            return;
        }
        boolean delivered = false;
        try (DeliveryLock lock = store.tryLock(manifest.pipelineBuildId, null)) {
            if (lock == null) {
                schedule(() -> deliverPipeline(manifest, key), System.currentTimeMillis() + RETRY_BASE_MS);
                return;
            }
            if (store.isComplete(manifest.pipelineBuildId)) {
                scheduled.remove(key);
                return;
            }
            DeliveryProgress progress = store.progress(manifest.pipelineBuildId, null);
            if (!progress.webhookSent) {
                DeliveryResult result = client.sendWebhookPayloadWithRetries(manifest.pipelineWebhookPayload,
                        manifest.pipelineId, apiKey(manifest), manifest.ddSite);
                if (result != DeliveryResult.SUCCESS) {
                    retry(manifest.pipelineBuildId, null, progress, result, () -> deliverPipeline(manifest, key));
                    return;
                }
                progress.webhookSent = true;
                store.saveProgress(manifest.pipelineBuildId, null, progress);
            }
            delivered = true;
        } catch (LogDeliveryException ex) {
            LOG.warn("Could not deliver CI pipeline webhook for build " + manifest.pipelineBuildId, ex);
            retryFailure(manifest.pipelineBuildId, null, ex.getResult(),
                    () -> deliverPipeline(manifest, key));
        } catch (Exception ex) {
            LOG.error("Could not deliver CI pipeline webhook for build " + manifest.pipelineBuildId, ex);
            retryUnexpected(manifest.pipelineBuildId, null, () -> deliverPipeline(manifest, key));
        }
        if (delivered) {
            scheduled.remove(key);
            scheduleJobs(manifest);
        }
    }

    private void deliverJob(DeliveryManifest manifest, DeliveryJob job, String key) {
        if (store.isComplete(manifest.pipelineBuildId)) {
            scheduled.remove(key);
            return;
        }
        boolean delivered = false;
        try (DeliveryLock lock = store.tryLock(manifest.pipelineBuildId, job.buildId)) {
            if (lock == null) {
                schedule(() -> deliverJob(manifest, job, key), System.currentTimeMillis() + RETRY_BASE_MS);
                return;
            }
            if (store.isComplete(manifest.pipelineBuildId)) {
                scheduled.remove(key);
                return;
            }
            DeliveryProgress progress = store.progress(manifest.pipelineBuildId, job.buildId);
            if (!progress.webhookSent) {
                SBuild build = buildsManager.findBuildInstanceById(job.buildId);
                if (build == null) {
                    throw new LogDeliveryException(DeliveryResult.BLOCKED,
                            "TeamCity build log is unavailable for build " + job.buildId);
                }
                String apiKey = apiKey(manifest);
                reporter.sendLogs(build, manifest.pipelineId, job.jobId, apiKey, manifest.ddSite,
                        progress.lastAcknowledgedLine, line -> {
                            progress.lastAcknowledgedLine = line;
                            store.saveProgress(manifest.pipelineBuildId, job.buildId, progress);
                        });
                DeliveryResult result = client.sendWebhookPayloadWithRetries(job.webhookPayload,
                        job.jobId, apiKey, manifest.ddSite);
                if (result != DeliveryResult.SUCCESS) {
                    retry(manifest.pipelineBuildId, job.buildId, progress, result,
                            () -> deliverJob(manifest, job, key));
                    return;
                }
                progress.webhookSent = true;
                store.saveProgress(manifest.pipelineBuildId, job.buildId, progress);
            }
            delivered = true;
        } catch (LogDeliveryException ex) {
            LOG.warn("Could not deliver CI logs for job " + job.jobId, ex);
            retryFailure(manifest.pipelineBuildId, job.buildId, ex.getResult(),
                    () -> deliverJob(manifest, job, key));
        } catch (Exception ex) {
            LOG.error("Could not deliver CI logs for job " + job.jobId, ex);
            retryUnexpected(manifest.pipelineBuildId, job.buildId,
                    () -> deliverJob(manifest, job, key));
        }
        if (delivered) {
            scheduled.remove(key);
            AtomicInteger remaining = remainingJobs.get(manifest.pipelineBuildId);
            if (remaining != null && remaining.decrementAndGet() == 0) {
                recoverPending();
            }
        }
    }

    private String apiKey(DeliveryManifest manifest) {
        SBuild pipeline = buildsManager.findBuildInstanceById(manifest.pipelineBuildId);
        if (pipeline == null) {
            throw new LogDeliveryException(DeliveryResult.BLOCKED,
                    "TeamCity pipeline build is unavailable: " + manifest.pipelineBuildId);
        }
        try {
            return projectHandler.getProjectParameters(pipeline).apiKey();
        } catch (IllegalArgumentException ex) {
            throw new LogDeliveryException(DeliveryResult.BLOCKED,
                    "TeamCity Datadog configuration is unavailable for build " + manifest.pipelineBuildId);
        }
    }

    private void retryFailure(long pipelineBuildId, Long jobBuildId, DeliveryResult result, Runnable task) {
        try {
            DeliveryProgress progress = store.progress(pipelineBuildId, jobBuildId);
            retry(pipelineBuildId, jobBuildId, progress, result, task);
        } catch (RuntimeException ex) {
            LOG.error("Could not persist retry state for CI log delivery " + pipelineBuildId, ex);
            schedule(task, System.currentTimeMillis() + RETRY_MAX_MS);
        }
    }

    private void retryUnexpected(long pipelineBuildId, Long jobBuildId, Runnable task) {
        retryFailure(pipelineBuildId, jobBuildId, DeliveryResult.RETRYABLE_FAILURE, task);
    }

    private void retry(long pipelineBuildId, Long jobBuildId, DeliveryProgress progress,
                       DeliveryResult result, Runnable task) {
        progress.failedCycles++;
        progress.blocked = result == DeliveryResult.BLOCKED;
        long delay = progress.blocked ? BLOCKED_RETRY_MS :
                Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(progress.failedCycles - 1, 4));
        if (!progress.blocked) {
            delay += ThreadLocalRandom.current().nextLong(Math.max(1, delay / 10));
        }
        progress.nextAttemptAtMillis = System.currentTimeMillis() + delay;
        store.saveProgress(pipelineBuildId, jobBuildId, progress);
        LOG.warn("CI log delivery for build " + pipelineBuildId +
                (jobBuildId == null ? " pipeline webhook" : " job " + jobBuildId) +
                " remains pending; retrying after " + delay + " ms");
        schedule(task, progress.nextAttemptAtMillis);
    }
}
