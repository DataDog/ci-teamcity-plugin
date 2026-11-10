/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BuildUtilsTest {
    @Test
    public void separatesAgentDependencyAndPreparationWaits() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        when(build.getStatisticValues()).thenReturn(statistics);
        when(build.getQueuedDate()).thenReturn(new Date(0));
        when(build.getStartDate()).thenReturn(new Date(65000));
        statistics.put("queueWaitReason:There_are_no_idle_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:Build_dependencies_have_not_been_built_yet", BigDecimal.valueOf(60000));
        statistics.put("queueWaitReason:Checking_for_changes_is_in_progress", BigDecimal.valueOf(2500));

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isEqualTo(5000);
        assertThat(waitTimes.dependencyWaitTimeMs()).isEqualTo(60000);
        assertThat(waitTimes.preparationTimeMs()).isEqualTo(2500);
    }

    @Test
    public void reportsZeroAgentWaitWhenOnlyDependenciesDelayedTheBuild() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:Build_dependencies_have_not_been_built_yet", BigDecimal.valueOf(60000));
        statistics.put("queueWaitReason:Waiting_for_the_start_of_the_first_build_in_the_build_chain", BigDecimal.valueOf(2000));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isZero();
        assertThat(waitTimes.dependencyWaitTimeMs()).isEqualTo(62000);
        assertThat(waitTimes.preparationTimeMs()).isZero();
    }

    @Test
    public void reportsZeroWhenNoWaitReasonsWereRecorded() {
        SBuild build = mock(SBuild.class);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isZero();
        assertThat(waitTimes.dependencyWaitTimeMs()).isZero();
        assertThat(waitTimes.preparationTimeMs()).isZero();
    }

    @Test
    public void includesCloudAgentStartupAndLegacyAgentWaitReasons() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:No_available_agents", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:Waiting_for_starting_agent", BigDecimal.valueOf(2000));
        statistics.put("queueWaitReason:There_are_no_compatible_agents_which_can_run_this_build_and_match_the_specified_restrictions", BigDecimal.valueOf(1000));
        statistics.put("queueWaitReason:Checking_for_changes_is_in_progress", BigDecimal.valueOf(3000));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isEqualTo(8000);
        assertThat(waitTimes.dependencyWaitTimeMs()).isZero();
        assertThat(waitTimes.preparationTimeMs()).isEqualTo(3000);
    }

    @Test
    public void includesRestrictedAndReservedAgentWaitReasons() {
        String[] reasons = {
            "There_are_no_idle_compatible_agents_in_the_selected_agent_pool_which_can_run_this_build",
            "There_are_no_idle_compatible_agents_with_the_selected_cloud_image_which_can_run_this_build",
            "The_agent_selected_for_the_build_is_either_incompatible_or_unavailable",
            "This_build_is_waiting_for_the_agent_used_for_building_its_dependency_",
            "There_are_no_agents_suitable_for_this_build_and_builds_depending_on_it_",
            "This_build_cannot_start_because_it_s_agents_are_reserved_by_other_builds_"
        };
        for (String reason : reasons) {
            SBuild build = mock(SBuild.class);
            when(build.getStatisticValues()).thenReturn(singletonMap("queueWaitReason:" + reason, BigDecimal.valueOf(1500)));

            BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
            assertThat(waitTimes.queueTimeMs()).as(reason).isEqualTo(1500);
            assertThat(waitTimes.dependencyWaitTimeMs()).as(reason).isZero();
            assertThat(waitTimes.preparationTimeMs()).as(reason).isZero();
            assertThat(waitTimes.reasonTimesMs())
                .containsEntry("no_idle_compatible_agents", 0L).hasSize(2);
        }
    }

    @Test
    public void includesAgentUpgradeWaitInBothKeyFormats() {
        for (String separator : new String[] {"_", "__"}) {
            SBuild build = mock(SBuild.class);
            Map<String, BigDecimal> statistics = new HashMap<>();
            statistics.put("queueWaitReason:The_selected_agent_is_outdated" + separator + "waiting_for_upgrade", BigDecimal.valueOf(2000));
            statistics.put("queueWaitReason:All_compatible_agents_are_outdated" + separator + "waiting_for_upgrade", BigDecimal.valueOf(3000));
            when(build.getStatisticValues()).thenReturn(statistics);

            BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
            assertThat(waitTimes.queueTimeMs()).as(separator).isEqualTo(5000);
            assertThat(waitTimes.reasonTimesMs())
                .containsEntry("selected_agent_upgrade", 2000L)
                .containsEntry("agents_upgrade", 3000L);
        }
    }

    @Test
    public void includesQueuedVcsAndSettingsPreparation() {
        String[] reasons = {
            "Waiting_to_start_checking_for_changes",
            "Checking_for_changes_is_in_progress",
            "A_build_that_is_dependent_on_this_one_is_waiting_to_start_collecting_changes",
            "A_build_that_is_dependent_on_this_one_is_collecting_changes",
            "Waiting_for_the_changes_collection_in_other_queued_builds",
            "Build_settings_have_not_been_finalized",
            "Build_settings_have_not_been_finalized_in_some_of_the_build_chain_builds",
            "Waiting_for_the_build_settings_finalization_in_other_queued_builds",
            "Waiting_for_pending_UI_settings_changes_to_complete"
        };
        for (String reason : reasons) {
            SBuild build = mock(SBuild.class);
            when(build.getStatisticValues()).thenReturn(singletonMap("queueWaitReason:" + reason, BigDecimal.valueOf(1250)));

            BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
            assertThat(waitTimes.preparationTimeMs()).as(reason).isEqualTo(1250);
            assertThat(waitTimes.queueTimeMs()).as(reason).isZero();
            assertThat(waitTimes.dependencyWaitTimeMs()).as(reason).isZero();
        }
    }

    @Test
    public void excludesOtherQueueDelaysAndRunningBuildPreparation() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:The_maximum_number_of_running_builds_for_this_configuration_is_reached", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:Build_is_waiting_for_the_following_resource_database", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:Waiting_for_the_build_queue_distribution_process", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:Unknown_future_wait_reason", BigDecimal.valueOf(5000));
        statistics.put("buildStageDuration:sourcesUpdate", BigDecimal.valueOf(5000));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isZero();
        assertThat(waitTimes.dependencyWaitTimeMs()).isZero();
        assertThat(waitTimes.preparationTimeMs()).isZero();
    }

    @Test
    public void reportsPositiveReasonDurationsInMilliseconds() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:There_are_no_idle_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(1234));
        statistics.put("queueWaitReason:The_agents_connectivity_with_the_server_has_not_been_verified_yet", BigDecimal.valueOf(56));
        statistics.put("queueWaitReason:Build_dependencies_have_not_been_built_yet", BigDecimal.valueOf(9876));
        statistics.put("queueWaitReason:Checking_for_changes_is_in_progress", BigDecimal.valueOf(45));
        statistics.put("queueWaitReason:The_maximum_number_of_running_builds_for_this_configuration_is_reached", BigDecimal.valueOf(101));
        statistics.put("buildStageDuration:sourcesUpdate", BigDecimal.valueOf(9999));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.reasonTimesMs())
            .hasSize(5)
            .containsEntry("no_idle_compatible_agents", 1234L)
            .containsEntry("agent_connectivity", 56L)
            .containsEntry("dependencies_unfinished", 9876L)
            .containsEntry("vcs_check_running", 45L)
            .containsEntry("configuration_limit", 101L);
    }

    @Test
    public void keepsZeroCapacityAndOmitsOtherNonPositiveReasons() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("BuildDuration", BigDecimal.valueOf(1000));
        statistics.put("queueWaitReason:Waiting_for_starting_agent", BigDecimal.ZERO);
        statistics.put("queueWaitReason:The_agents_connectivity_with_the_server_has_not_been_verified_yet", BigDecimal.valueOf(-1));
        statistics.put("queueWaitReason:Build_dependencies_have_not_been_built_yet", BigDecimal.valueOf(-2));
        statistics.put("queueWaitReason:Checking_for_changes_is_in_progress", BigDecimal.valueOf(-3));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isZero();
        assertThat(waitTimes.dependencyWaitTimeMs()).isZero();
        assertThat(waitTimes.preparationTimeMs()).isZero();
        assertThat(waitTimes.reasonTimesMs())
            .hasSize(1)
            .containsEntry("no_idle_compatible_agents", 0L);
    }

    @Test
    public void doesNotInventCapacityWhenStatisticsAreUnavailable() {
        SBuild build = mock(SBuild.class);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.reasonTimesMs()).isEmpty();
    }

    @Test
    public void separatesExactCapacityFromLegacyAndRestrictedAgentReasons() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:There_are_no_idle_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(5000));
        statistics.put("queueWaitReason:There_are_no_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(2000));
        statistics.put("queueWaitReason:There_are_no_compatible_agents_which_can_run_this_build_and_match_the_specified_restrictions", BigDecimal.valueOf(3000));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.reasonTimesMs())
            .hasSize(3)
            .containsEntry("no_idle_compatible_agents", 5000L)
            .containsEntry("no_compatible_agents", 2000L)
            .containsEntry("agent_restrictions", 3000L);
        assertThat(waitTimes.queueTimeMs()).isEqualTo(10000);
    }

    @Test
    public void doesNotTreatLegacyAgentUnavailabilityAsZeroCapacity() {
        for (String reason : new String[] {"No_available_agents", "There_are_no_compatible_agents_which_can_run_this_build"}) {
            SBuild build = mock(SBuild.class);
            when(build.getStatisticValues()).thenReturn(singletonMap("queueWaitReason:" + reason, BigDecimal.valueOf(2000)));

            BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
            assertThat(waitTimes.reasonTimesMs())
                .hasSize(1)
                .containsValue(2000L)
                .doesNotContainKey("no_idle_compatible_agents");
        }
    }

    @Test
    public void combinesResourceDetailsAndUnknownReasonsIntoBoundedNames() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:Build_is_waiting_for_the_following_resource_database_1", BigDecimal.valueOf(250));
        statistics.put("queueWaitReason:Build_is_waiting_for_the_following_resource_database_2", BigDecimal.valueOf(300));
        statistics.put("queueWaitReason:New_extension_reason_build_123", BigDecimal.valueOf(100));
        statistics.put("queueWaitReason:New_extension_reason_build_456", BigDecimal.valueOf(200));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.reasonTimesMs())
            .hasSize(3)
            .containsEntry("shared_resource", 550L)
            .containsEntry("other", 300L)
            .containsEntry("no_idle_compatible_agents", 0L);
    }

    @Test
    public void combinesPersistedAndCurrentPartialReasonDurations() {
        SBuild build = mock(SBuild.class);
        Map<String, BigDecimal> statistics = new HashMap<>();
        statistics.put("queueWaitReason:There_are_no_idle_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(1000));
        statistics.put("queueWaitReason:CVPThere_are_no_idle_compatible_agents_which_can_run_this_build", BigDecimal.valueOf(234));
        statistics.put("queueWaitReason:Build_dependencies_have_not_been_built_yet", BigDecimal.valueOf(600));
        statistics.put("queueWaitReason:CVPBuild_dependencies_have_not_been_built_yet", BigDecimal.valueOf(50));
        when(build.getStatisticValues()).thenReturn(statistics);

        BuildUtils.QueueWaitTimes waitTimes = BuildUtils.queueWaitTimes(build);
        assertThat(waitTimes.queueTimeMs()).isEqualTo(1234);
        assertThat(waitTimes.dependencyWaitTimeMs()).isEqualTo(650);
        assertThat(waitTimes.reasonTimesMs())
            .hasSize(2)
            .containsEntry("no_idle_compatible_agents", 1234L)
            .containsEntry("dependencies_unfinished", 650L);
    }
}
