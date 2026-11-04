/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    // In TeamCity, the last composite build of the chain might start slightly after the first build of the chain.
    // This is a temporary hack to include an offset of some seconds to not incorrectly
    // filter out the first build of the chain if it started slightly before the last composite build.
    private static final int PIPELINE_START_OFFSET_MS = 3000;

    private static final List<String> AGENT_WAIT_REASONS = Arrays.asList(
        "No_available_agents",
        "The_agents_connectivity_with_the_server_has_not_been_verified_yet",
        "There_are_no_idle_compatible_agents_which_can_run_this_build",
        "There_are_no_compatible_agents_which_can_run_this_build",
        "There_are_no_compatible_agents_which_can_run_this_build_and_match_the_specified_restrictions",
        "There_are_no_enabled_compatible_agents_which_can_run_this_build",
        "The_selected_agent_is_disconnected",
        "The_selected_agent_is_not_compatible",
        "The_selected_agent_is_from_inaccessible_pool_of_agents",
        "The_selected_agent_is_outdated__waiting_for_upgrade",
        "The_selected_agent_is_outdated_and_cannot_be_upgraded_to_a_newer_version",
        "All_compatible_agents_are_outdated__waiting_for_upgrade",
        "All_compatible_agents_are_outdated_and_cannot_be_upgraded_to_a_newer_version",
        "Waiting_for_starting_agent");

    private static final List<String> DEPENDENCY_WAIT_REASONS = Arrays.asList(
        "Build_dependencies_have_not_been_built_yet",
        "Waiting_for_the_start_of_the_first_build_in_the_build_chain");

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
        return waitTimeMs(build, AGENT_WAIT_REASONS);
    }

    public static long dependencyWaitTimeMs(SBuild build) {
        return waitTimeMs(build, DEPENDENCY_WAIT_REASONS);
    }

    private static long waitTimeMs(SBuild build, List<String> reasons) {
        return reasons.stream()
            .map(reason -> build.getStatisticValue("queueWaitReason:" + reason))
            .filter(Objects::nonNull)
            .mapToLong(BigDecimal::longValue)
            .sum();
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
