/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.util.EventDispatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DatadogServerAdapterShutdownTest {
    @Mock private EventDispatcher<BuildServerListener> eventListener;
    @Mock private BuildsManager buildsManager;
    @Mock private BuildChainProcessor buildChainProcessor;
    @Mock private ProjectHandler projectHandler;
    @Mock private ExecutorService logReportingExecutor;
    @Mock private ExecutorService clientExecutor;

    private DatadogServerAdapter adapter;

    @Before
    public void setUp() {
        adapter = new DatadogServerAdapter(eventListener, buildsManager, buildChainProcessor, projectHandler,
                logReportingExecutor, clientExecutor);
    }

    @Test
    public void drainsLogJobsBeforeShuttingDownWebhookExecutor() throws InterruptedException {
        when(logReportingExecutor.awaitTermination(3, TimeUnit.MINUTES)).thenReturn(true);
        when(clientExecutor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(true);

        adapter.serverShutdown();

        InOrder order = inOrder(logReportingExecutor, clientExecutor);
        order.verify(logReportingExecutor).shutdown();
        order.verify(logReportingExecutor).awaitTermination(3, TimeUnit.MINUTES);
        order.verify(clientExecutor).shutdown();
        order.verify(clientExecutor).awaitTermination(30, TimeUnit.SECONDS);
        verify(logReportingExecutor, never()).shutdownNow();
        verify(clientExecutor, never()).shutdownNow();
    }

    @Test(timeout = 10000)
    public void waitsForActiveLogJobToEnqueueItsWebhook() throws InterruptedException {
        ExecutorService logs = Executors.newFixedThreadPool(1);
        ExecutorService webhooks = Executors.newFixedThreadPool(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch webhookFinished = new CountDownLatch(1);
        Thread shutdownThread = new Thread(() -> new DatadogServerAdapter(eventListener, buildsManager,
                buildChainProcessor, projectHandler, logs, webhooks).serverShutdown());
        try {
            logs.execute(() -> {
                started.countDown();
                try {
                    release.await();
                    webhooks.execute(webhookFinished::countDown);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            shutdownThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!logs.isShutdown() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertThat(logs.isShutdown()).isTrue();
            assertThat(shutdownThread.isAlive()).isTrue();

            release.countDown();
            shutdownThread.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(shutdownThread.isAlive()).isFalse();
            assertThat(webhookFinished.getCount()).isZero();
            assertThat(logs.isTerminated()).isTrue();
            assertThat(webhooks.isTerminated()).isTrue();
        } finally {
            release.countDown();
            logs.shutdownNow();
            webhooks.shutdownNow();
            shutdownThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    public void stopsQueuedLogJobsAfterDrainTimeout() throws InterruptedException {
        when(logReportingExecutor.awaitTermination(3, TimeUnit.MINUTES)).thenReturn(false);
        when(logReportingExecutor.shutdownNow()).thenReturn(Collections.singletonList(() -> {}));
        when(clientExecutor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(true);

        adapter.serverShutdown();

        verify(logReportingExecutor).shutdownNow();
        verify(clientExecutor).shutdown();
        verify(clientExecutor).awaitTermination(30, TimeUnit.SECONDS);
    }

    @Test
    public void stopsWebhookJobsAfterDrainTimeout() throws InterruptedException {
        when(logReportingExecutor.awaitTermination(3, TimeUnit.MINUTES)).thenReturn(true);
        when(clientExecutor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(false);
        when(clientExecutor.shutdownNow()).thenReturn(Collections.emptyList());

        adapter.serverShutdown();

        verify(clientExecutor).shutdownNow();
    }

    @Test
    public void interruptsBothExecutorsWhenShutdownIsInterrupted() throws InterruptedException {
        when(logReportingExecutor.awaitTermination(3, TimeUnit.MINUTES)).thenThrow(new InterruptedException());

        try {
            adapter.serverShutdown();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(logReportingExecutor).shutdownNow();
            verify(clientExecutor).shutdownNow();
        } finally {
            Thread.interrupted();
        }
    }
}
