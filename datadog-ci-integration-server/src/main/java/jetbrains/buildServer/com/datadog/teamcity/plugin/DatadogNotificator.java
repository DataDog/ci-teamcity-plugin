package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildDependenciesManager;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline.PipelineStatus;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildUtils.*;

@Component
public class DatadogNotificator extends NotificatorAdapter {

    private static final String NOTIFIER_TYPE = "DATADOG_NOTIFIER";
    private static final String DISPLAY_NAME = "Datadog Notificator";
    private static final Logger LOG = Logger.getInstance(DatadogNotificator.class.getName());

    private final BuildsManager buildsManager;
    private final DatadogClient datadogClient;
    private final ProjectHandler projectHandler;
    private final SBuildServer buildServer;
    private final BuildDependenciesManager dependenciesManager;

    public DatadogNotificator(NotificatorRegistry notificatorRegistry,
                              BuildsManager buildsManager,
                              DatadogClient datadogClient,
                              ProjectHandler projectHandler,
                              SBuildServer buildServer,
                              BuildDependenciesManager dependenciesManager) {
        this.buildsManager = buildsManager;
        this.datadogClient = datadogClient;
        this.projectHandler = projectHandler;
        this.buildServer = buildServer;
        this.dependenciesManager = dependenciesManager;

        notificatorRegistry.register(this);
    }

    @Override
    public String getNotificatorType() {
        return NOTIFIER_TYPE;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public void notifyBuildSuccessful(SRunningBuild build, Set<SUser> users) {
        onFinishedBuild(build);
    }

    @Override
    public void notifyBuildFailed(SRunningBuild build, Set<SUser> users) {
        onFinishedBuild(build);
    }

    @VisibleForTesting
    protected void onFinishedBuild(SBuild build) {
        SBuild finishedBuild = buildsManager.findBuildInstanceById(build.getBuildId());
        if (finishedBuild == null) {
            // This should not happen, but better to check for it anyway
            LOG.error("Could not find build with ID: " + build.getBuildId());
            return;
        }

        if (shouldBeIgnored(finishedBuild)) {
            LOG.info(format("Ignoring build %s as it's a composite build but not the final build in the chain", finishedBuild.getFullName()));
            return;
        }

        Optional<String> optionalProjectID = Optional.ofNullable(finishedBuild.getProjectId());
        ProjectParameters params = projectHandler.getProjectParameters(optionalProjectID);

        CIEntity ciEntity = createEntity(finishedBuild);
        datadogClient.sendWebhook(ciEntity, params.apiKey(), params.ddSite());
    }

    private CIEntity createEntity(SBuild build) {
        if (isPipelineBuild(build)) {
            return createPipelineEntity(build);
        } else {
            return createJobEntity(build);
        }
    }

    private Pipeline createPipelineEntity(SBuild build) {
        return new Pipeline(
                build.getFullName(),
                buildURL(build),
                toRFC3339(build.getStartDate()),
                toRFC3339(build.getFinishDate()),
                String.valueOf(build.getBuildId()),
                String.valueOf(build.getBuildId()),
                false, //TODO partial retry detection needs to be implemented still (CIAPP-5347)
                build.getBuildStatus().isSuccessful() ? PipelineStatus.SUCCESS : PipelineStatus.ERROR
        );
    }

    private Job createJobEntity(SBuild build) {
        PipelineInfo pipelineInfo = dependenciesManager.getPipelineBuildForJob(build)
                .map(pipelineBuild -> new PipelineInfo(String.valueOf(pipelineBuild.getBuildId()), pipelineBuild.getFullName()))
                .orElseThrow(() -> new IllegalArgumentException(format("Could not find pipeline build for job build %s", build)));

        return new Job(
                build.getFullName(),
                buildURL(build),
                toRFC3339(build.getStartDate()),
                toRFC3339(build.getFinishDate()),
                pipelineInfo.id,
                pipelineInfo.name,
                String.valueOf(build.getBuildId()),
                build.getBuildStatus().isSuccessful() ? JobStatus.SUCCESS : JobStatus.ERROR
        );
    }

    private String buildURL(SBuild build) {
        return format("%s/build/%s", buildServer.getRootUrl(), build.getBuildId());
    }

    private static class PipelineInfo {
        private final String id;
        private final String name;

        private PipelineInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
