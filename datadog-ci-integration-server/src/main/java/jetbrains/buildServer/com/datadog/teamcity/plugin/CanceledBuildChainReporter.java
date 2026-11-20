/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class CanceledBuildChainReporter {
    private static final Logger LOG = Logger.getInstance(CanceledBuildChainReporter.class.getName());
    // Internal override: grace from the composite's finish, only for dependencies still queued without an agent.
    static final String QUEUED_TIMEOUT_PROPERTY = "datadog.ci.canceled_composite.queued_timeout_seconds";
    private static final long DEFAULT_QUEUED_TIMEOUT_SECONDS = 300;

    private final BuildsManager buildsManager;
    private final BuildPromotionManager promotionManager;
    private final BuildChainProcessor processor;
    private final SecurityContextEx securityContext;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final LongSupplier queuedTimeoutMs;
    private final Map<Long, PendingChain> pending = new HashMap<>();
    private boolean stopped;

    @Autowired
    public CanceledBuildChainReporter(BuildsManager buildsManager, BuildPromotionManager promotionManager,
                                      BuildChainProcessor processor, SecurityContextEx securityContext,
                                      @Qualifier("canceledBuildExecutor") ScheduledExecutorService executor) {
        this(buildsManager, promotionManager, processor, securityContext, executor, Clock.systemUTC(),
                CanceledBuildChainReporter::queuedTimeoutMs);
    }

    CanceledBuildChainReporter(BuildsManager buildsManager, BuildPromotionManager promotionManager,
                               BuildChainProcessor processor, SecurityContextEx securityContext,
                               ScheduledExecutorService executor, Clock clock, LongSupplier queuedTimeoutMs) {
        this.buildsManager = buildsManager;
        this.promotionManager = promotionManager;
        this.processor = processor;
        this.securityContext = securityContext;
        this.executor = executor;
        this.clock = clock;
        this.queuedTimeoutMs = queuedTimeoutMs;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::checkPending, 10, 10, TimeUnit.SECONDS);
    }

    public synchronized void report(SBuild composite) {
        if (stopped) {
            return;
        }
        securityContext.runAsSystemUnchecked((SecurityContextEx.RunAsAction) () -> {
            pending.computeIfAbsent(composite.getBuildId(), id -> new PendingChain(queuedTimeoutMs.getAsLong()));
            checkPendingAsSystem();
        });
    }

    synchronized void checkPending() {
        if (!stopped) {
            try {
                securityContext.runAsSystemUnchecked((SecurityContextEx.RunAsAction) this::checkPendingAsSystem);
            } catch (RuntimeException ex) {
                LOG.warn("Cannot check pending canceled composites; will check again", ex);
            }
        }
    }

    private void checkPendingAsSystem() {
        Iterator<Map.Entry<Long, PendingChain>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingChain> entry = iterator.next();
            try {
                SBuild composite = buildsManager.findBuildInstanceById(entry.getKey());
                if (composite == null || composite.getFinishDate() == null) {
                    LOG.warn("Cannot resolve finished canceled composite " + entry.getKey() + "; will check again");
                    continue;
                }
                PendingChain chain = entry.getValue();
                composite.getBuildPromotion().getAllDependencies().forEach(dependency -> chain.dependencies.add(dependency.getId()));
                List<Long> expiredQueued = new ArrayList<>();
                boolean ready = true;
                long elapsed = clock.millis() - composite.getFinishDate().getTime();
                for (long promotionId : chain.dependencies) {
                    if (!isSettled(promotionId, elapsed >= chain.queuedTimeoutMs, expiredQueued)) {
                        ready = false;
                    }
                }
                if (ready) {
                    if (!expiredQueued.isEmpty()) {
                        LOG.warn("Reporting canceled composite " + entry.getKey()
                                + " without queued dependency promotions after the grace period: " + expiredQueued);
                    }
                    iterator.remove();
                    try {
                        processor.process(composite);
                    } catch (RuntimeException ex) {
                        // Processing can already have submitted some webhooks; preserve the existing delivery semantics.
                        LOG.warn("Cannot hand off canceled composite " + entry.getKey() + " for reporting", ex);
                    }
                }
            } catch (RuntimeException ex) {
                LOG.warn("Cannot report canceled composite " + entry.getKey() + "; will check again", ex);
            }
        }
    }

    private boolean isSettled(long promotionId, boolean queueExpired, List<Long> expiredQueued) {
        BuildPromotion promotion = promotionManager.findPromotionById(promotionId);
        if (promotion == null) {
            LOG.warn("Cannot resolve dependency promotion " + promotionId + "; will check again");
            return false;
        }
        Long buildId = promotion.getAssociatedBuildId();
        if (buildId != null) {
            // Callback objects can remain SRunningBuild instances after completion; reload the finished record.
            SBuild build = buildsManager.findBuildInstanceById(buildId);
            return build != null && build.getFinishDate() != null;
        }
        SQueuedBuild queued = promotion.getQueuedBuild();
        if (queued != null && queued.getBuildAgent() != null) {
            return false;
        }
        if (promotion.isCanceled() || promotion.isFailedToCollectChanges()) {
            return true;
        }
        if (queued != null) {
            if (queueExpired) {
                expiredQueued.add(promotionId);
            }
            return queueExpired;
        }
        LOG.warn("Cannot determine state of dependency promotion " + promotionId + "; will check again");
        return false;
    }

    public void stop() throws InterruptedException {
        synchronized (this) {
            stopped = true;
            if (!pending.isEmpty()) {
                LOG.warn("Discarding in-memory canceled composites during shutdown: " + pending.keySet());
                pending.clear();
            }
        }
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    private static long queuedTimeoutMs() {
        return queuedTimeoutMs(TeamCityProperties.getProperty(QUEUED_TIMEOUT_PROPERTY,
                String.valueOf(DEFAULT_QUEUED_TIMEOUT_SECONDS)));
    }

    static long queuedTimeoutMs(String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds >= 0) {
                return Math.multiplyExact(seconds, 1000);
            }
        } catch (IllegalArgumentException | ArithmeticException ignored) {
        }
        LOG.warn("Invalid " + QUEUED_TIMEOUT_PROPERTY + "; using " + DEFAULT_QUEUED_TIMEOUT_SECONDS + " seconds");
        return TimeUnit.SECONDS.toMillis(DEFAULT_QUEUED_TIMEOUT_SECONDS);
    }

    private static class PendingChain {
        private final Set<Long> dependencies = new HashSet<>();
        private final long queuedTimeoutMs;

        private PendingChain(long queuedTimeoutMs) {
            this.queuedTimeoutMs = queuedTimeoutMs;
        }
    }
}
