/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class LogExecutorCapacityTest {
    @Parameterized.Parameters(name = "{0} pending jobs")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {{100}, {101}, {1000}});
    }

    private final int pendingJobs;

    public LogExecutorCapacityTest(int pendingJobs) {
        this.pendingJobs = pendingJobs;
    }

    @Test(timeout = 30000)
    public void queuesEveryJobWhileAllWorkersAreBusy() throws InterruptedException {
        ExecutorService executor = new DatadogConfiguration().logReportingExecutor();
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch completedJobs = new CountDownLatch(pendingJobs);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < pendingJobs; i++) {
                executor.execute(completedJobs::countDown);
            }

            assertThat(((ThreadPoolExecutor) executor).getQueue().size()).isEqualTo(pendingJobs);
            releaseWorkers.countDown();
            assertThat(completedJobs.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWorkers.countDown();
            executor.shutdownNow();
        }
    }
}
