package jetbrains.buildServer.com.datadog.teamcity.plugin.model;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;

import java.text.SimpleDateFormat;
import java.util.Date;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private BuildUtils() { }

    public static boolean isPipelineBuild(SBuild build) {
        return isPipelineBuild(build.getBuildPromotion());
    }

    public static boolean isPipelineBuild(BuildPromotion build) {
        return build.isCompositeBuild() && build.getNumberOfDependedOnMe() == 0;
    }

    public static boolean isJobBuild(SBuild build) {
        return !build.isCompositeBuild();
    }

    public static boolean isPartialRetry(SBuild build) {
        TriggeredBy trigger = build.getTriggeredBy();
        return trigger.isTriggeredByUser() ||
                trigger.getParameters().getOrDefault("type", "").equals("retry");
    }

    public static String buildID(SBuild build) {
        return String.valueOf(build.getBuildId());
    }

    public static boolean hasChanges(SBuild build) {
        return !build.getContainingChanges().isEmpty();
    }

    /**
     * We ignore a composite build if it's not the last in the chain. This is because we don't want to report them on
     * the trace as they are not run in agents or have any steps (their only purpose is to aggregate previous results).
     */
    public static boolean shouldBeIgnored(SBuild build) {
        return build.isCompositeBuild() && build.getBuildPromotion().getNumberOfDependedOnMe() > 0;
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
