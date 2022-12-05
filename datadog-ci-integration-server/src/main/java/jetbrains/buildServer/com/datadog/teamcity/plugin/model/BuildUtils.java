package jetbrains.buildServer.com.datadog.teamcity.plugin.model;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;

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

    public static long queueTimeMs(SBuild build) {
        return build.getStartDate().getTime() - build.getQueuedDate().getTime();
    }

    public static List<String> dependenciesIds(SBuild build) {
        return build.getBuildPromotion().getDependencies().stream()
                .filter(dep -> dep.getDependOn().getAssociatedBuild() != null)
                .map(dep -> String.valueOf(dep.getDependOn().getAssociatedBuildId()))
                .collect(toList());
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
