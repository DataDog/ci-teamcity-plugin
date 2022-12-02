package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildUtils.shouldBeIgnored;

@Component
public class DatadogNotificator extends NotificatorAdapter {

    private static final String NOTIFIER_TYPE = "DATADOG_NOTIFIER";
    private static final String DISPLAY_NAME = "Datadog Notificator";
    private static final Logger LOG = Logger.getInstance(DatadogNotificator.class.getName());

    private final BuildsManager buildsManager;
    private final DatadogClient datadogClient;
    private final ProjectHandler projectHandler;
    private final CIEntityFactory entityCreator;

    public DatadogNotificator(NotificatorRegistry notificatorRegistry,
                              BuildsManager buildsManager,
                              DatadogClient datadogClient,
                              ProjectHandler projectHandler,
                              CIEntityFactory entityCreator) {
        this.buildsManager = buildsManager;
        this.datadogClient = datadogClient;
        this.projectHandler = projectHandler;
        this.entityCreator = entityCreator;

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

        //TODO find other cases to be ignored
        if (shouldBeIgnored(finishedBuild)) {
            LOG.info(format("Ignoring build %s as it's a composite build but not the final build in the chain", finishedBuild.getFullName()));
            return;
        }

        Optional<String> optionalProjectID = Optional.ofNullable(finishedBuild.getProjectId());
        ProjectParameters params = projectHandler.getProjectParameters(optionalProjectID);

        CIEntity ciEntity = entityCreator.create(finishedBuild);
        datadogClient.sendWebhook(ciEntity, params.apiKey(), params.ddSite());
    }
}
