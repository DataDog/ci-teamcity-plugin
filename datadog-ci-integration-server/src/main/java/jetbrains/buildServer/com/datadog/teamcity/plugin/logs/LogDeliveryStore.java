/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.ServerPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Component
public class LogDeliveryStore {
    private static final Logger LOG = Logger.getInstance(LogDeliveryStore.class.getName());
    private final Path directory;
    private final ObjectMapper mapper;

    @Autowired
    public LogDeliveryStore(ServerPaths serverPaths, ObjectMapper mapper) {
        this(serverPaths.getPluginDataDirectory().toPath().resolve("datadog-ci-integration-logs"), mapper);
    }

    LogDeliveryStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    public synchronized boolean create(DeliveryManifest manifest) {
        Path path = manifestPath(manifest.pipelineBuildId);
        if (Files.exists(path) || Files.exists(donePath(manifest.pipelineBuildId))) {
            return false;
        }
        write(path, manifest);
        return true;
    }

    public boolean contains(long pipelineBuildId) {
        return Files.exists(manifestPath(pipelineBuildId)) || Files.exists(donePath(pipelineBuildId));
    }

    public boolean isComplete(long pipelineBuildId) {
        return Files.exists(donePath(pipelineBuildId));
    }

    public synchronized long recoveryStartMillis() {
        Path path = directory.resolve("recovery-start.json");
        if (Files.exists(path)) {
            try {
                return mapper.readValue(path.toFile(), Long.class);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read CI log recovery position", ex);
            }
        }
        long startedAt = System.currentTimeMillis();
        write(path, startedAt);
        return startedAt;
    }

    public synchronized void markRecovered(long scanStartedAtMillis) {
        write(directory.resolve("recovery-start.json"), scanStartedAtMillis);
    }

    public void purgeCompleted(Duration age) {
        if (!Files.exists(directory)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - age.toMillis();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "pipeline-*.done")) {
            for (Path path : paths) {
                if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            LOG.warn("Could not clean completed CI log delivery records", ex);
        }
    }

    public List<DeliveryManifest> list() {
        List<DeliveryManifest> manifests = new ArrayList<>();
        if (!Files.exists(directory)) {
            return manifests;
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "pipeline-*.json")) {
            for (Path path : paths) {
                if (!path.getFileName().toString().matches("pipeline-[0-9]+\\.json")) {
                    continue;
                }
                long pipelineBuildId = Long.parseLong(path.getFileName().toString()
                        .replace("pipeline-", "").replace(".json", ""));
                if (Files.exists(donePath(pipelineBuildId))) {
                    continue;
                }
                try {
                    manifests.add(mapper.readValue(path.toFile(), DeliveryManifest.class));
                } catch (IOException ex) {
                    LOG.error("Could not read pending CI log delivery manifest " + path, ex);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not list pending CI log deliveries", ex);
        }
        return manifests;
    }

    public DeliveryProgress progress(long pipelineBuildId, Long jobBuildId) {
        Path path = progressPath(pipelineBuildId, jobBuildId);
        if (!Files.exists(path)) {
            return new DeliveryProgress();
        }
        try {
            return mapper.readValue(path.toFile(), DeliveryProgress.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read CI log delivery progress from " + path, ex);
        }
    }

    public synchronized void saveProgress(long pipelineBuildId, Long jobBuildId, DeliveryProgress progress) {
        write(progressPath(pipelineBuildId, jobBuildId), progress);
    }

    public DeliveryLock tryLock(long pipelineBuildId, Long jobBuildId) {
        try {
            Files.createDirectories(directory);
            FileChannel channel = FileChannel.open(lockPath(pipelineBuildId, jobBuildId),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new DeliveryLock(channel, lock);
            } catch (OverlappingFileLockException ex) {
                channel.close();
                return null;
            } catch (RuntimeException | IOException ex) {
                channel.close();
                throw ex;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not lock a CI log delivery", ex);
        }
    }

    public synchronized void complete(DeliveryManifest manifest) {
        long pipelineBuildId = manifest.pipelineBuildId;
        try {
            Files.createDirectories(directory);
            Path done = donePath(pipelineBuildId);
            if (!Files.exists(done)) {
                write(done, "complete");
            }
            Files.deleteIfExists(manifestPath(pipelineBuildId));
            Files.deleteIfExists(progressPath(pipelineBuildId, null));
            for (DeliveryJob job : manifest.jobs) {
                Files.deleteIfExists(progressPath(pipelineBuildId, job.buildId));
                Files.deleteIfExists(lockPath(pipelineBuildId, job.buildId));
            }
            Files.deleteIfExists(lockPath(pipelineBuildId, null));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not complete CI log delivery for build " + pipelineBuildId, ex);
        }
    }

    private Path manifestPath(long pipelineBuildId) {
        return directory.resolve("pipeline-" + pipelineBuildId + ".json");
    }

    private Path donePath(long pipelineBuildId) {
        return directory.resolve("pipeline-" + pipelineBuildId + ".done");
    }

    private Path progressPath(long pipelineBuildId, Long jobBuildId) {
        return directory.resolve("pipeline-" + pipelineBuildId +
                (jobBuildId == null ? "" : "-job-" + jobBuildId) + ".state.json");
    }

    private Path lockPath(long pipelineBuildId, Long jobBuildId) {
        return directory.resolve("pipeline-" + pipelineBuildId +
                (jobBuildId == null ? "" : "-job-" + jobBuildId) + ".lock");
    }

    private void write(Path path, Object value) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".delivery-", ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(mapper.writeValueAsBytes(value));
                output.getFD().sync();
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist CI log delivery state at " + path, ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    LOG.warn("Could not remove temporary CI log delivery file " + temporary, ex);
                }
            }
        }
    }
}
