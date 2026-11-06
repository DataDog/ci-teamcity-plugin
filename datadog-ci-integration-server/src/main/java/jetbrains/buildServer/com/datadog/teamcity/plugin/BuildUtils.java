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
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    // In TeamCity, the last composite build of the chain might start slightly after the first build of the chain.
    // This is a temporary hack to include an offset of some seconds to not incorrectly
    // filter out the first build of the chain if it started slightly before the last composite build.
    private static final int PIPELINE_START_OFFSET_MS = 3000;

    private static final String QUEUE_WAIT_REASON_PREFIX = "queueWaitReason:";

    private enum WaitGroup { AGENT, DEPENDENCY, PREPARATION, EXCLUDED }

    // Known built-in reasons; extensions can publish additional descriptions.
    private enum WaitReason {
        // Older servers report a generic agent-unavailability bucket.
        NO_AVAILABLE_AGENTS(WaitGroup.AGENT, "No_available_agents"),
        // Candidate agents must pass the server's connectivity check before use.
        AGENT_CONNECTIVITY(WaitGroup.AGENT, "The_agents_connectivity_with_the_server_has_not_been_verified_yet"),
        // This exact statistic isolates lack of idle compatible runners.
        NO_IDLE_COMPATIBLE_AGENTS(WaitGroup.AGENT, "There_are_no_idle_compatible_agents_which_can_run_this_build"),
        // Older servers do not distinguish saturation from generic incompatibility.
        NO_COMPATIBLE_AGENTS(WaitGroup.AGENT, "There_are_no_compatible_agents_which_can_run_this_build"),
        // Additional agent-selection restrictions leave no eligible runner.
        AGENT_RESTRICTIONS(WaitGroup.AGENT, "There_are_no_compatible_agents_which_can_run_this_build_and_match_the_specified_restrictions"),
        // Idle runners outside the requested pool cannot run this build.
        AGENT_POOL(WaitGroup.AGENT, "There_are_no_idle_compatible_agents_in_the_selected_agent_pool_which_can_run_this_build"),
        // The build requires an available runner from a particular cloud image.
        AGENT_CLOUD_IMAGE(WaitGroup.AGENT, "There_are_no_idle_compatible_agents_with_the_selected_cloud_image_which_can_run_this_build"),
        // Compatible runners are disabled and cannot accept work.
        AGENTS_DISABLED(WaitGroup.AGENT, "There_are_no_enabled_compatible_agents_which_can_run_this_build"),
        // The specifically selected runner is offline.
        SELECTED_AGENT_DISCONNECTED(WaitGroup.AGENT, "The_selected_agent_is_disconnected"),
        // The specifically selected runner does not satisfy the build requirements.
        SELECTED_AGENT_INCOMPATIBLE(WaitGroup.AGENT, "The_selected_agent_is_not_compatible"),
        // The selected runner's pool is unavailable to this project.
        SELECTED_AGENT_POOL(WaitGroup.AGENT, "The_selected_agent_is_from_inaccessible_pool_of_agents"),
        // Newer servers combine selected-runner incompatibility and unavailability.
        SELECTED_AGENT_UNAVAILABLE(WaitGroup.AGENT, "The_agent_selected_for_the_build_is_either_incompatible_or_unavailable"),
        // The selected runner must finish upgrading before accepting work.
        SELECTED_AGENT_UPGRADE(WaitGroup.AGENT, "The_selected_agent_is_outdated_waiting_for_upgrade"),
        // The selected runner needs an upgrade that cannot currently proceed.
        SELECTED_AGENT_UPGRADE_BLOCKED(WaitGroup.AGENT, "The_selected_agent_is_outdated_and_cannot_be_upgraded_to_a_newer_version"),
        // Every compatible runner is waiting for an upgrade.
        AGENTS_UPGRADE(WaitGroup.AGENT, "All_compatible_agents_are_outdated_waiting_for_upgrade"),
        // Every compatible runner needs an upgrade that cannot currently proceed.
        AGENTS_UPGRADE_BLOCKED(WaitGroup.AGENT, "All_compatible_agents_are_outdated_and_cannot_be_upgraded_to_a_newer_version"),
        // A cloud runner is still being provisioned or started.
        AGENT_STARTUP(WaitGroup.AGENT, "Waiting_for_starting_agent"),
        // A delegated execution backend has not started its executor build yet.
        EXECUTOR_STARTUP(WaitGroup.AGENT, "Waiting_for_starting_executor_build"),
        // Same-agent affinity requires the runner used by a dependency.
        DEPENDENCY_AGENT(WaitGroup.AGENT, "This_build_is_waiting_for_the_agent_used_for_building_its_dependency_"),
        // No runner satisfies the requirements of the whole same-agent group.
        SAME_AGENT_GROUP(WaitGroup.AGENT, "There_are_no_agents_suitable_for_this_build_and_builds_depending_on_it_"),
        // Other builds have reserved all runners eligible for this build.
        AGENTS_RESERVED(WaitGroup.AGENT, "This_build_cannot_start_because_it_s_agents_are_reserved_by_other_builds_"),

        // Required snapshot dependencies have not finished.
        DEPENDENCIES_UNFINISHED(WaitGroup.DEPENDENCY, "Build_dependencies_have_not_been_built_yet"),
        // A composite waits for its first dependency to start the chain.
        FIRST_DEPENDENCY_NOT_STARTED(WaitGroup.DEPENDENCY, "Waiting_for_the_start_of_the_first_build_in_the_build_chain"),

        // Server-side VCS change collection has not started.
        VCS_CHECK_PENDING(WaitGroup.PREPARATION, "Waiting_to_start_checking_for_changes"),
        // Server-side VCS change collection is still running.
        VCS_CHECK_RUNNING(WaitGroup.PREPARATION, "Checking_for_changes_is_in_progress"),
        // Another build in the chain must begin collecting changes first.
        CHAIN_VCS_CHECK_PENDING(WaitGroup.PREPARATION, "A_build_that_is_dependent_on_this_one_is_waiting_to_start_collecting_changes"),
        // Change collection elsewhere in the chain prevents this build starting.
        CHAIN_VCS_CHECK_RUNNING(WaitGroup.PREPARATION, "A_build_that_is_dependent_on_this_one_is_collecting_changes"),
        // Queue optimization waits for other candidate builds to collect changes.
        OTHER_VCS_CHECKS(WaitGroup.PREPARATION, "Waiting_for_the_changes_collection_in_other_queued_builds"),
        // Versioned build settings have not been finalized for this build.
        SETTINGS_PENDING(WaitGroup.PREPARATION, "Build_settings_have_not_been_finalized"),
        // Settings finalization elsewhere in the chain is incomplete.
        CHAIN_SETTINGS_PENDING(WaitGroup.PREPARATION, "Build_settings_have_not_been_finalized_in_some_of_the_build_chain_builds"),
        // Queue optimization waits for other candidates to finalize settings.
        OTHER_SETTINGS_PENDING(WaitGroup.PREPARATION, "Waiting_for_the_build_settings_finalization_in_other_queued_builds"),
        // Pending UI edits must be applied to versioned settings before starting.
        UI_SETTINGS_PENDING(WaitGroup.PREPARATION, "Waiting_for_pending_UI_settings_changes_to_complete"),

        // The build configuration has reached its concurrent-build limit.
        CONFIGURATION_LIMIT(WaitGroup.EXCLUDED, "The_maximum_number_of_running_builds_for_this_configuration_is_reached"),
        // The branch has reached its concurrent-build limit.
        BRANCH_LIMIT(WaitGroup.EXCLUDED, "The_maximum_number_of_running_builds_in_this_branch_is_reached"),
        // Project-level admission throttles how many builds can start together.
        PROJECT_START_LIMIT(WaitGroup.EXCLUDED, "Reached_the_limit_for_a_number_of_simultaneously_starting_builds_in_this_project"),
        // Licensing prevents further agent authorization.
        AGENT_LICENSE_LIMIT(WaitGroup.EXCLUDED, "Maximum_number_of_authorized_agents_exceeded"),
        // Licensing prevents using additional build configurations.
        CONFIGURATION_LICENSE_LIMIT(WaitGroup.EXCLUDED, "Maximum_number_of_build_configurations_exceeded"),
        // Licensing prevents using additional pipelines.
        PIPELINE_LICENSE_LIMIT(WaitGroup.EXCLUDED, "Maximum_number_of_pipelines_exceeded"),
        // A licensed concurrency cap prevents admission even with idle runners.
        CONCURRENCY_LICENSE_LIMIT(WaitGroup.EXCLUDED, "Maximum_number_of_concurrent_builds_reached"),
        // A shared-resource lock or quota has not been released.
        SHARED_RESOURCE(WaitGroup.EXCLUDED, "Build_is_waiting_for_a_resource", "Build_is_waiting_for_the_following_resource"),
        // A human must approve the build before the server can admit it.
        BUILD_APPROVAL(WaitGroup.EXCLUDED, "This_build_should_be_approved_before_it_may_start"),
        // Project trust rules require explicit approval of the build.
        UNTRUSTED_BUILD_APPROVAL(WaitGroup.EXCLUDED, "Build_marked_as_untrusted_based_on_current_project_settings"),
        // The server could not evaluate the build's approval requirements.
        APPROVAL_CHECK_FAILED(WaitGroup.EXCLUDED, "Failed_to_determine_if_build_requires_approval"),
        // A coordinating composite has not been allowed to start the chain.
        DEPENDENT_COMPOSITE_START(WaitGroup.EXCLUDED, "Start_of_the_dependent_composite_build"),
        // The requested future start time has not arrived; the timestamp varies.
        SCHEDULED_START(WaitGroup.EXCLUDED, "This_build_is_scheduled_for_"),
        // The server has not reached this build in its queue-distribution pass.
        QUEUE_DISTRIBUTION(WaitGroup.EXCLUDED, "Waiting_for_the_build_queue_distribution_process"),
        // Queue synchronization delays distribution across server nodes.
        QUEUE_SYNCHRONIZATION(WaitGroup.EXCLUDED, "Build_queue_synchronization_delay"),
        // Restarting the server delayed queue processing.
        SERVER_STARTUP(WaitGroup.EXCLUDED, "TeamCity_server_startup_delay"),
        // Server dispatch preparation is outside VCS/settings preparation.
        BUILD_DISPATCH(WaitGroup.EXCLUDED, "Preparing_a_build_for_a_start_on_an_agent", "Preparing_the_composite_build_for_a_start"),
        // A build-start extension vetoed admission; its details vary.
        EXTENSION_BLOCKED(WaitGroup.EXCLUDED, "Extension_blocked_starting_of_the_build_"),
        // The collector exceeded its per-build reason limit, losing attribution.
        REASON_OVERFLOW(WaitGroup.EXCLUDED, "Overflow");

        private final WaitGroup group;
        private final String[] keyPrefixes;

        WaitReason(WaitGroup group, String... keyPrefixes) {
            this.group = group;
            this.keyPrefixes = keyPrefixes;
        }

        private boolean matches(String key) {
            return matchingPrefixLength(key) >= 0;
        }

        private int matchingPrefixLength(String key) {
            return Arrays.stream(keyPrefixes).filter(key::startsWith).mapToInt(String::length).max().orElse(-1);
        }

        private String metricName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

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
        return waitTimeMs(build, WaitGroup.AGENT);
    }

    public static long dependencyWaitTimeMs(SBuild build) {
        return waitTimeMs(build, WaitGroup.DEPENDENCY);
    }

    public static long preparationTimeMs(SBuild build) {
        return waitTimeMs(build, WaitGroup.PREPARATION);
    }

    public static Map<String, Long> queueWaitReasonTimesMs(SBuild build) {
        Map<String, BigDecimal> statistics = build.getStatisticValues();
        Map<String, Long> reasons = new TreeMap<>();
        if (statistics.isEmpty()) {
            return reasons;
        }

        String capacityKey = WaitReason.NO_IDLE_COMPATIBLE_AGENTS.keyPrefixes[0];
        String capacityMetric = WaitReason.NO_IDLE_COMPATIBLE_AGENTS.metricName();
        reasons.put(capacityMetric, 0L);
        boolean capacityRecorded = false;
        boolean legacyAgentDelay = false;

        for (Map.Entry<String, BigDecimal> statistic : statistics.entrySet()) {
            if (!statistic.getKey().startsWith(QUEUE_WAIT_REASON_PREFIX)) {
                continue;
            }
            String key = normalizeWaitReasonKey(statistic.getKey());
            capacityRecorded |= key.equals(capacityKey);
            long durationMs = statistic.getValue().longValue();
            if (durationMs <= 0) {
                continue;
            }

            WaitReason reason = Arrays.stream(WaitReason.values())
                .filter(candidate -> candidate.matches(key))
                .filter(candidate -> candidate != WaitReason.NO_IDLE_COMPATIBLE_AGENTS || key.equals(capacityKey))
                .max(Comparator.comparingInt(candidate -> candidate.matchingPrefixLength(key)))
                .orElse(null);
            legacyAgentDelay |= reason == WaitReason.NO_AVAILABLE_AGENTS || reason == WaitReason.NO_COMPATIBLE_AGENTS;
            // Unknown descriptions share one field so build/resource IDs cannot create metric names.
            String metricName = reason == null ? "other" : reason.metricName();
            reasons.merge(metricName, durationMs, Long::sum);
        }

        if (legacyAgentDelay && !capacityRecorded) {
            // Legacy generic delays cannot establish whether idle capacity was the blocker.
            reasons.remove(capacityMetric);
        }
        return reasons;
    }

    private static long waitTimeMs(SBuild build, WaitGroup group) {
        return build.getStatisticValues().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(QUEUE_WAIT_REASON_PREFIX))
            .filter(entry -> {
                String key = normalizeWaitReasonKey(entry.getKey());
                return Arrays.stream(WaitReason.values()).anyMatch(reason -> reason.group == group && reason.matches(key));
            })
            .mapToLong(entry -> entry.getValue().longValue())
            .sum();
    }

    private static String normalizeWaitReasonKey(String key) {
        String reason = key.substring(QUEUE_WAIT_REASON_PREFIX.length());
        // CVP stores an additional partial duration for the current reason.
        if (reason.startsWith("CVP")) {
            reason = reason.substring(3);
        }
        // TeamCity versions differ in whether consecutive punctuation produces multiple underscores.
        return reason.replaceAll("_+", "_");
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
