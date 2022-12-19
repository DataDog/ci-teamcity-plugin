package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import org.springframework.stereotype.Component;

import java.util.Set;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildName;

@Component
public class DatadogNotifier extends NotificatorAdapter {

    private static final String NOTIFIER_TYPE = "DATADOG_NOTIFIER";
    private static final String DISPLAY_NAME = "Datadog Notifier";
    private static final Logger LOG = Logger.getInstance(DatadogNotifier.class.getName());

    private final BuildsManager buildsManager;
    private final BuildChainProcessor buildChainProcessor;

    public DatadogNotifier(NotificatorRegistry notificatorRegistry,
                           BuildsManager buildsManager,
                           BuildChainProcessor buildChainProcessor) {
        this.buildsManager = buildsManager;
        this.buildChainProcessor = buildChainProcessor;

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
        if (!isLastCompositeBuild(build)) {
            LOG.info(format("Ignoring build with id '%s' and name '%s'", buildID(build), buildName(build)));
            return;
        }

        // At this point, we know it's the final composite build of the chain
        SBuild pipelineBuild = buildsManager.findBuildInstanceById(build.getBuildId());
        if (pipelineBuild == null) {
            // This should not happen, but better to check for it anyway
            LOG.error("The TeamCity server could not find build with ID: " + build.getBuildId());
            return;
        }

        buildChainProcessor.process(pipelineBuild);
    }

    private boolean isLastCompositeBuild(SBuild build) {
        return build.isCompositeBuild() &&
            build.getBuildPromotion().getNumberOfDependedOnMe() == 0 &&
            !build.isPersonal();
    }
}
