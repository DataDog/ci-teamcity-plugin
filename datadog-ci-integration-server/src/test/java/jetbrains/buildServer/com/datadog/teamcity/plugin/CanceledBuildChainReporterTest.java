/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CanceledBuildChainReporterTest {
    private static final long FINISHED_AT = 1_000_000;
    @Mock private BuildsManager builds;
    @Mock private BuildPromotionManager promotions;
    @Mock private BuildChainProcessor processor;
    @Mock private SecurityContextEx security;
    @Mock private ScheduledExecutorService executor;
    private final MutableClock clock = new MutableClock();
    @Mock private SBuild composite;
    @Mock private BuildPromotion root;
    @Mock private BuildPromotion dependency;
    private CanceledBuildChainReporter reporter;

    @Before
    public void setUp() {
        doAnswer(invocation -> {
            ((SecurityContextEx.RunAsAction) invocation.getArguments()[0]).run();
            return null;
        }).when(security).runAsSystemUnchecked(any(SecurityContextEx.RunAsAction.class));
        when(composite.getBuildId()).thenReturn(10L);
        when(composite.getFinishDate()).thenReturn(new Date(FINISHED_AT));
        when(composite.getBuildPromotion()).thenReturn(root);
        when(builds.findBuildInstanceById(10)).thenReturn(composite);
        doReturn(singletonList(dependency)).when(root).getAllDependencies();
        when(dependency.getId()).thenReturn(200L);
        when(dependency.getAssociatedBuildId()).thenReturn(null);
        doReturn(dependency).when(promotions).findPromotionById(200);
        clock.now = FINISHED_AT;
        reporter = new CanceledBuildChainReporter(builds, promotions, processor, security, executor, clock, () -> 300_000);
    }

    @Test
    public void waitsForRunningDependencyAndReloadsFinishedBuild() {
        SBuild running = running(200, 20);
        reporter.report(composite);
        reporter.report(composite);
        clock.now = FINISHED_AT + TimeUnit.HOURS.toMillis(12);
        reporter.checkPending();
        verifyZeroInteractions(processor);

        SBuild finished = mock(SBuild.class);
        when(finished.getFinishDate()).thenReturn(new Date(FINISHED_AT + 15_000));
        when(builds.findBuildInstanceById(20)).thenReturn(finished);
        reporter.checkPending();
        reporter.checkPending();

        assertThat(running.getFinishDate()).isNull();
        assertThat(composite.getFinishDate()).isEqualTo(new Date(FINISHED_AT));
        verify(processor).process(composite);
    }

    @Test
    public void reportsImmediatelyWhenEverythingHasFinished() {
        SBuild finished = running(200, 20);
        when(finished.getFinishDate()).thenReturn(new Date(FINISHED_AT - 1));
        reporter.report(composite);
        verify(processor).process(composite);
    }

    @Test
    public void interruptedDependencyStillBlocksUntilItsWorkFinishes() {
        SBuild build = running(200, 20);
        when(dependency.isCanceled()).thenReturn(true);
        reporter.report(composite);
        verifyZeroInteractions(processor);
        when(build.getFinishDate()).thenReturn(new Date(FINISHED_AT + 1000));
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void queuedGraceExpiresFromCompositeFinishWithoutBeingResetByChecks() {
        queued(dependency);
        reporter.report(composite);
        clock.now = FINISHED_AT + 299_999;
        reporter.checkPending();
        reporter.report(composite);
        verifyZeroInteractions(processor);
        clock.now = FINISHED_AT + 300_000;
        reporter.checkPending();
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void zeroGraceDoesNotWaitForQueuedDependencies() {
        queued(dependency);
        reporter = new CanceledBuildChainReporter(builds, promotions, processor, security, executor, clock, () -> 0);
        reporter.report(composite);
        verify(processor).process(composite);
    }

    @Test
    public void queuedBuildStartingBeforeDeadlineWaitsUntilFinished() {
        queued(dependency);
        reporter.report(composite);
        clock.now = FINISHED_AT + 299_000;
        SBuild build = running(200, 20);
        reporter.checkPending();
        clock.now = FINISHED_AT + 600_000;
        reporter.checkPending();
        verifyZeroInteractions(processor);
        when(build.getFinishDate()).thenReturn(new Date(FINISHED_AT + 600_000));
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void assignedBuildHasNoQueueTimeoutEvenBeforeBuildRecordExists() {
        SQueuedBuild queued = queued(dependency);
        when(queued.getBuildAgent()).thenReturn(mock(SBuildAgent.class));
        clock.now = FINISHED_AT + 600_000;
        reporter.report(composite);
        verifyZeroInteractions(processor);
    }

    @Test
    public void expiredQueuedDependencyCanStillStartWhileAnotherJobIsRunning() {
        BuildPromotion other = mock(BuildPromotion.class);
        when(other.getId()).thenReturn(300L);
        when(promotions.findPromotionById(300)).thenReturn(other);
        when(other.getAssociatedBuildId()).thenReturn(30L);
        SBuild otherBuild = mock(SBuild.class);
        when(builds.findBuildInstanceById(30)).thenReturn(otherBuild);
        doReturn(Arrays.asList(dependency, other)).when(root).getAllDependencies();
        queued(dependency);
        reporter.report(composite);
        clock.now = FINISHED_AT + 300_000;
        reporter.checkPending();
        SBuild lateBuild = running(200, 20);
        when(otherBuild.getFinishDate()).thenReturn(new Date(FINISHED_AT + 301_000));
        reporter.checkPending();
        verifyZeroInteractions(processor);
        when(lateBuild.getFinishDate()).thenReturn(new Date(FINISHED_AT + 400_000));
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void canceledBeforeStartIsSettled() {
        when(dependency.isCanceled()).thenReturn(true);
        reporter.report(composite);
        verify(processor).process(composite);
    }

    @Test
    public void failureToCollectChangesIsSettled() {
        when(dependency.isFailedToCollectChanges()).thenReturn(true);
        reporter.report(composite);
        verify(processor).process(composite);
    }

    @Test
    public void missingPromotionIsRecheckedInsteadOfAssumedFinished() {
        when(promotions.findPromotionById(200)).thenReturn(null);
        reporter.report(composite);
        clock.now = FINISHED_AT + 600_000;
        reporter.checkPending();
        verifyZeroInteractions(processor);
        doReturn(dependency).when(promotions).findPromotionById(200);
        when(dependency.isCanceled()).thenReturn(true);
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void missingAssociatedBuildDoesNotFallThroughToQueueTimeout() {
        when(dependency.getAssociatedBuildId()).thenReturn(20L);
        queued(dependency);
        clock.now = FINISHED_AT + 600_000;
        reporter.report(composite);
        verifyZeroInteractions(processor);
    }

    @Test
    public void unresolvedStateIsRechecked() {
        reporter.report(composite);
        clock.now = FINISHED_AT + 600_000;
        reporter.checkPending();
        verifyZeroInteractions(processor);
        when(dependency.isFailedToCollectChanges()).thenReturn(true);
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void transientLookupFailureDoesNotStopFollowingChecks() {
        when(promotions.findPromotionById(200)).thenThrow(new IllegalStateException("temporarily unavailable"));
        reporter.report(composite);
        verifyZeroInteractions(processor);
        doReturn(dependency).when(promotions).findPromotionById(200);
        when(dependency.isCanceled()).thenReturn(true);
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test
    public void securityFailureDoesNotTerminatePolling() {
        queued(dependency);
        reporter.report(composite);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (fail.getAndSet(false)) {
                throw new IllegalStateException("temporarily unavailable");
            }
            ((SecurityContextEx.RunAsAction) invocation.getArguments()[0]).run();
            return null;
        }).when(security).runAsSystemUnchecked(any(SecurityContextEx.RunAsAction.class));
        when(dependency.isCanceled()).thenReturn(true);
        reporter.checkPending();
        verifyZeroInteractions(processor);
        reporter.checkPending();
        verify(processor).process(composite);
    }

    @Test(timeout = 5000)
    public void concurrentChecksSubmitOnlyOnce() throws Exception {
        SBuild build = running(200, 20);
        reporter.report(composite);
        when(build.getFinishDate()).thenReturn(new Date(FINISHED_AT + 15_000));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable check = () -> {
                try {
                    release.await();
                    reporter.checkPending();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            };
            Future<?> first = threads.submit(check);
            Future<?> second = threads.submit(check);
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            verify(processor, times(1)).process(composite);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    public void shutdownDiscardsPendingAndPreventsNewWork() throws InterruptedException {
        running(200, 20);
        reporter.report(composite);
        when(executor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(true);
        reporter.stop();
        reporter.report(composite);
        reporter.checkPending();
        verify(executor).shutdown();
        verifyZeroInteractions(processor);
    }

    @Test
    public void startsOnePeriodicCheck() {
        reporter.start();
        verify(executor).scheduleWithFixedDelay(any(Runnable.class), org.mockito.Matchers.eq(10L),
                org.mockito.Matchers.eq(10L), org.mockito.Matchers.eq(TimeUnit.SECONDS));
    }

    @Test
    public void internalQueueTimeoutAcceptsZeroAndCustomValues() {
        assertThat(CanceledBuildChainReporter.queuedTimeoutMs("0")).isZero();
        assertThat(CanceledBuildChainReporter.queuedTimeoutMs("15")).isEqualTo(15_000);
    }

    @Test
    public void invalidQueueTimeoutFallsBackToFiveMinutes() {
        for (String value : Arrays.asList("invalid", "-1", "9223372036854775807")) {
            assertThat(CanceledBuildChainReporter.queuedTimeoutMs(value)).isEqualTo(300_000);
        }
    }

    @Test
    public void dependenciesStartingAfterTheBatchWasReportedAreNotBackfilled() {
        queued(dependency);
        reporter.report(composite);
        clock.now = FINISHED_AT + 300_000;
        reporter.checkPending();
        SBuild build = running(200, 20);
        when(build.getFinishDate()).thenReturn(new Date(FINISHED_AT + 400_000));
        reporter.checkPending();
        verify(processor, times(1)).process(composite);
    }

    @Test
    public void doesNotResubmitBatchIfProcessingFailsAfterPartiallyHandingOff() {
        when(dependency.isCanceled()).thenReturn(true);
        doThrow(new IllegalStateException("partial handoff")).when(processor).process(composite);
        reporter.report(composite);
        reporter.checkPending();
        verify(processor, times(1)).process(composite);
    }

    private static class MutableClock extends Clock {
        private long now;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private SBuild running(long promotionId, long buildId) {
        BuildPromotion promotion = promotionId == 200 ? dependency : promotions.findPromotionById(promotionId);
        when(promotion.getAssociatedBuildId()).thenReturn(buildId);
        SBuild build = mock(SBuild.class);
        when(builds.findBuildInstanceById(buildId)).thenReturn(build);
        return build;
    }

    private SQueuedBuild queued(BuildPromotion promotion) {
        SQueuedBuild build = mock(SQueuedBuild.class);
        when(promotion.getQueuedBuild()).thenReturn(build);
        return build;
    }
}
