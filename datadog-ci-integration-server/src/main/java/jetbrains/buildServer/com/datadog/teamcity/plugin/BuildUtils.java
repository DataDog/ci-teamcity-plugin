package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    // We add some offset when considering the start of the pipeline, as TeamCity might start
    // the first job slightly before the pipeline
    private static final int PIPELINE_START_OFFSET_MS = 3000;

    private BuildUtils() { }

    public static boolean isPartialRetry(SBuild pipelineBuild) {
        boolean isAutomaticRetry = pipelineBuild.getTriggeredBy()
                .getParameters()
                .getOrDefault("type", "").equals("retry");

        // We check if any of the jobs were started before the composite build (accounting for the offset)
        Date pipelineStartWithOffset = pipelineStartWithOffset(pipelineBuild);
        boolean isReusingBuilds = pipelineBuild.getBuildPromotion().getAllDependencies().stream()
                .filter(build -> build.getAssociatedBuild() != null)
                .anyMatch(build -> build.getAssociatedBuild().getStartDate().before(pipelineStartWithOffset));

        return isAutomaticRetry || isReusingBuilds;
    }

    public static Date pipelineStartWithOffset(SBuild pipelineBuild) {
        if (pipelineBuild.getStartDate().getTime() <= PIPELINE_START_OFFSET_MS) {
            return new Date(0);
        }

        return new Date(pipelineBuild.getStartDate().getTime() - PIPELINE_START_OFFSET_MS);
    }

    public static String buildID(SBuild build) {
        return String.valueOf(build.getBuildId());
    }

    public static boolean hasChanges(SBuild build) {
        return !build.getContainingChanges().isEmpty();
    }

    public static String buildName(SBuild build) {
        return build.getFullName();
    }

    public static long queueTimeMs(SBuild build) {
        return build.getStartDate().getTime() - build.getQueuedDate().getTime();
    }

    public static List<String> dependenciesIds(SBuild build) {
        return build.getBuildPromotion().getDependencies().stream()
                .filter(dep -> dep.getDependOn().getAssociatedBuild() != null)
                .map(dep -> buildID(dep.getDependOn().getAssociatedBuild()))
                .collect(toList());
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
