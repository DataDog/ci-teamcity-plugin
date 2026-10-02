/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

public class LogDeliveryStoreTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsProgressAcrossStoreInstancesAndIgnoresDuplicateManifests() {
        ObjectMapper mapper = new ObjectMapper();
        LogDeliveryStore first = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), mapper);
        DeliveryManifest manifest = new DeliveryManifest(17, "pipeline-17", "datad0g.com", "{}",
                Collections.singletonList(new DeliveryJob(18, "job-18", "{}")));

        assertThat(first.create(manifest)).isTrue();
        assertThat(first.create(manifest)).isFalse();
        DeliveryProgress progress = new DeliveryProgress();
        progress.lastAcknowledgedLine = 1000;
        progress.nextAttemptAtMillis = 12345;
        first.saveProgress(17, 18L, progress);

        LogDeliveryStore recovered = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), mapper);
        assertThat(recovered.list()).hasSize(1);
        assertThat(recovered.list().get(0).jobs.get(0).jobId).isEqualTo("job-18");
        assertThat(recovered.progress(17, 18L).lastAcknowledgedLine).isEqualTo(1000);
        assertThat(recovered.progress(17, 18L).nextAttemptAtMillis).isEqualTo(12345);
    }

    @Test
    public void completedPipelineCannotBeEnqueuedAgain() {
        LogDeliveryStore store = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), new ObjectMapper());
        DeliveryManifest manifest = new DeliveryManifest(17, "pipeline-17", "datad0g.com", "{}",
                Collections.singletonList(new DeliveryJob(18, "job-18", "{}")));
        store.create(manifest);

        store.complete(manifest);

        assertThat(store.list()).isEmpty();
        assertThat(store.create(manifest)).isFalse();
        assertThat(Files.exists(temporaryFolder.getRoot().toPath().resolve("pipeline-17.done"))).isTrue();
    }

    @Test
    public void onlyOneWorkerCanHoldAJobLock() throws Exception {
        LogDeliveryStore first = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), new ObjectMapper());
        LogDeliveryStore second = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), new ObjectMapper());

        try (DeliveryLock lock = first.tryLock(17, 18L)) {
            assertThat(lock).isNotNull();
            assertThat(second.tryLock(17, 18L)).isNull();
        }

        try (DeliveryLock lock = second.tryLock(17, 18L)) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    public void recoveryPositionSurvivesRestart() {
        LogDeliveryStore first = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), new ObjectMapper());
        long enabledAt = first.recoveryStartMillis();
        first.markRecovered(enabledAt + 1000);

        LogDeliveryStore recovered = new LogDeliveryStore(temporaryFolder.getRoot().toPath(), new ObjectMapper());

        assertThat(recovered.recoveryStartMillis()).isEqualTo(enabledAt + 1000);
    }
}
