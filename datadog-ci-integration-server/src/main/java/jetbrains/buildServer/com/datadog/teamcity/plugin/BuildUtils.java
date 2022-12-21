/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;

import java.text.SimpleDateFormat;
import java.util.Date;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    // In TeamCity, the last composite build of the chain might start slightly after the first build of the chain.
    // This is a temporary hack to include an offset of some seconds to not incorrectly
    // filter out the first build of the chain if it started slightly before the last composite build.
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

    public static String buildName(SBuild build) {
        return build.getFullName();
    }

    public static long queueTimeMs(SBuild build) {
        return build.getStartDate().getTime() - build.getQueuedDate().getTime();
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
