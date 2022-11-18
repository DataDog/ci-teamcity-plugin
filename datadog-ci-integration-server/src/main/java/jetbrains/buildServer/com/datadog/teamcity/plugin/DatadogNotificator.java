package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Set;

import static java.lang.String.format;

@Component
public class DatadogNotificator extends NotificatorAdapter {

    private static final String NOTIFIER_TYPE = "DATADOG_NOTIFIER";
    private static final String DISPLAY_NAME = "Datadog Notificator";
    private static final Logger LOG = Logger.getInstance(DatadogNotificator.class.getName());

    public DatadogNotificator(NotificatorRegistry notificatorRegistry) {
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
    public void notifyBuildSuccessful(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        LOG.info(format("Build '%s' successful", build.getFullName()));
    }
}
