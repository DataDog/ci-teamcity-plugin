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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BuildQueueTimeTest {
    @Test
    public void excludesDependencyWaitFromQueueTime() {
        SBuild build = mock(SBuild.class);
        when(build.getQueuedDate()).thenReturn(new Date(0));
        when(build.getStartDate()).thenReturn(new Date(65000));
        when(build.getStatisticValue("queueWaitReason:There_are_no_idle_compatible_agents_which_can_run_this_build"))
            .thenReturn(BigDecimal.valueOf(5000));
        when(build.getStatisticValue("queueWaitReason:Build_dependencies_have_not_been_built_yet"))
            .thenReturn(BigDecimal.valueOf(60000));

        assertThat(BuildUtils.queueTimeMs(build)).isEqualTo(5000);
        assertThat(BuildUtils.dependencyWaitTimeMs(build)).isEqualTo(60000);
    }

    @Test
    public void reportsZeroAgentWaitWhenOnlyDependenciesDelayedTheBuild() {
        SBuild build = mock(SBuild.class);
        when(build.getStatisticValue("queueWaitReason:Build_dependencies_have_not_been_built_yet"))
            .thenReturn(BigDecimal.valueOf(60000));
        when(build.getStatisticValue("queueWaitReason:Waiting_for_the_start_of_the_first_build_in_the_build_chain"))
            .thenReturn(BigDecimal.valueOf(2000));

        assertThat(BuildUtils.queueTimeMs(build)).isZero();
        assertThat(BuildUtils.dependencyWaitTimeMs(build)).isEqualTo(62000);
    }

    @Test
    public void reportsZeroWhenNoWaitReasonsWereRecorded() {
        SBuild build = mock(SBuild.class);

        assertThat(BuildUtils.queueTimeMs(build)).isZero();
        assertThat(BuildUtils.dependencyWaitTimeMs(build)).isZero();
    }

    @Test
    public void includesCloudAgentStartupAndLegacyAgentWaitReasons() {
        SBuild build = mock(SBuild.class);
        when(build.getStatisticValue("queueWaitReason:No_available_agents"))
            .thenReturn(BigDecimal.valueOf(5000));
        when(build.getStatisticValue("queueWaitReason:Waiting_for_starting_agent"))
            .thenReturn(BigDecimal.valueOf(2000));
        when(build.getStatisticValue("queueWaitReason:Checking_for_changes_is_in_progress"))
            .thenReturn(BigDecimal.valueOf(3000));

        assertThat(BuildUtils.queueTimeMs(build)).isEqualTo(7000);
        assertThat(BuildUtils.dependencyWaitTimeMs(build)).isZero();
    }
}
