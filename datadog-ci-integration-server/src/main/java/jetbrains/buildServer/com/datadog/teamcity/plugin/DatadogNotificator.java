package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.DatadogBuild;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.Pipeline;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;

@Component
public class DatadogNotificator extends NotificatorAdapter {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final String NOTIFIER_TYPE = "DATADOG_NOTIFIER";
    private static final String DISPLAY_NAME = "Datadog Notificator";
    private static final Logger LOG = Logger.getInstance(DatadogNotificator.class.getName());

    private final BuildsManager buildsManager;
    private final SBuildServer buildServer;
    private final DatadogClient datadogClient;
    private final ProjectHandler projectHandler;

    public DatadogNotificator(NotificatorRegistry notificatorRegistry,
                              BuildsManager buildsManager,
                              SBuildServer buildServer,
                              DatadogClient datadogClient,
                              ProjectHandler projectHandler) {
        this.buildsManager = buildsManager;
        this.buildServer = buildServer;
        this.datadogClient = datadogClient;
        this.projectHandler = projectHandler;

        notificatorRegistry.register(this);
    }

    @NotNull
    @Override
    public String getNotificatorType() {
        return NOTIFIER_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public void notifyBuildSuccessful(@NotNull SRunningBuild runningBuild, @NotNull Set<SUser> users) {
        SBuild build = buildsManager.findBuildInstanceById(runningBuild.getBuildId());
        if (build == null) {
            LOG.error("Could not find build with ID: " + runningBuild.getBuildId());
            return;
        }

        onFinishedBuild(DatadogBuild.fromBuild(build));
    }

    @Override
    public void notifyBuildFailed(@NotNull SRunningBuild runningBuild, @NotNull Set<SUser> users) {
        SBuild build = buildsManager.findBuildInstanceById(runningBuild.getBuildId());
        if (build == null) {
            LOG.error("Could not find build with ID: " + runningBuild.getBuildId());
            return;
        }

        onFinishedBuild(DatadogBuild.fromBuild(build));
    }

    @VisibleForTesting
    protected void onFinishedBuild(DatadogBuild build) {
        //TODO for now we are only generating payloads for pipelines (job support in CIAPP-5340)
        if (isPipelineBuild(build)) {
            Optional<String> optionalProjectID = Optional.ofNullable(build.projectID());
            ProjectParameters params = projectHandler.getProjectParameters(optionalProjectID);

            Pipeline pipeline = createPipeline(build);
            datadogClient.sendWebhook(pipeline, params.apiKey(), params.ddSite());
        } else {
            LOG.info("Job webhooks not supported yet");
        }
    }


    //TODO For now this method just checks for composite builds, it will be really implemented with CIAPP-5339.
    private boolean isPipelineBuild(DatadogBuild datadogBuild) {
        return datadogBuild.isComposite();
    }

    private Pipeline createPipeline(DatadogBuild datadogBuild) {
        return new Pipeline(
                datadogBuild.name(),
                format("%s/build/%s", buildServer.getRootUrl(), datadogBuild.id()),
                RFC_3339.format(datadogBuild.startDate()),
                RFC_3339.format(datadogBuild.finishDate()),
                String.valueOf(datadogBuild.id()),
                String.valueOf(datadogBuild.id()),
                false, //TODO partial retry detection needs to be implemented still (CIAPP-5347)
                datadogBuild.status().isSuccessful() ? Pipeline.PipelineStatus.SUCCESS : Pipeline.PipelineStatus.ERROR
        );
    }
}
