/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.BuildStep;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.BuildStep.StepStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildRunnerDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for build step extraction from TeamCity statistics API.
 * 
 * Implementation uses SBuild.getStatisticValues() which provides buildStageDuration:* entries.
 * All steps are marked as SUCCESS due to TeamCity API limitations (no step-level status available).
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildStepExtractionTest {

    @Mock
    private DatadogClient datadogClient;
    @Mock
    private ProjectHandler projectHandler;
    @Mock
    private GitInformationExtractor gitExtractor;
    @Mock
    private jetbrains.buildServer.serverSide.SBuildServer buildServer;
    @Mock
    private jetbrains.buildServer.serverSide.ServerSettings serverSettings;

    private BuildChainProcessor processor;

    @Before
    public void setUp() {
        processor = new BuildChainProcessor(buildServer, datadogClient, projectHandler, gitExtractor, serverSettings);
    }

    @Test
    public void shouldReturnEmptyListWhenNoStepsAvailable() {
        // Given: A build with no build stage statistics
        SBuild build = buildWithStatistics(new HashMap<>());

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Should have no steps
        assertThat(steps).isEmpty();
    }

    @Test
    public void shouldExtractVcsCheckoutStage() {
        // Given: A build with VCS checkout timing
        Map<String, BigDecimal> stats = new HashMap<>();
        stats.put("buildStageDuration:sourcesUpdate", new BigDecimal("4126"));
        SBuild build = buildWithStatistics(stats);

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Should extract checkout as a step
        assertThat(steps).hasSize(1);
        BuildStep step = steps.get(0);
        assertThat(step.getName()).isEqualTo("Checkout");
        assertThat(step.getDurationMs()).isEqualTo(4126L);
        assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCESS); // Always SUCCESS due to API limitation
        assertThat(step.getError()).isNull();
        // Updated regex to accept optional milliseconds
        assertThat(step.getStart()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?(Z|[+-]\\d{2}:\\d{2})");
        assertThat(step.getEnd()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?(Z|[+-]\\d{2}:\\d{2})");
    }

    @Test
    public void shouldExtractUserDefinedBuildSteps() {
        // Given: A build with two user-defined steps
        Map<String, BigDecimal> stats = new HashMap<>();
        stats.put("buildStageDuration:buildSteptest_step_1", new BigDecimal("126"));
        stats.put("buildStageDuration:buildSteptest_step_2", new BigDecimal("142"));
        
        SBuild build = buildWithStatistics(stats);
        mockBuildRunners(build, asList(
            runner("test_step_1", "test step 1"),
            runner("test_step_2", "test step 2")
        ));

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Should extract both steps
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getName()).isEqualTo("test step 1");
        assertThat(steps.get(0).getDurationMs()).isEqualTo(126L);
        assertThat(steps.get(1).getName()).isEqualTo("test step 2");
        assertThat(steps.get(1).getDurationMs()).isEqualTo(142L);
    }

    @Test
    public void shouldExtractAllBuildStagesInOrder() {
        // Given: A build with all stages
        Map<String, BigDecimal> stats = new HashMap<>();
        stats.put("buildStageDuration:sourcesUpdate", new BigDecimal("3908"));
        stats.put("buildStageDuration:toolsUpdating", new BigDecimal("1"));
        stats.put("buildStageDuration:firstStepPreparation", new BigDecimal("1"));
        stats.put("buildStageDuration:buildSteptest_step_1", new BigDecimal("109"));
        stats.put("buildStageDuration:buildFinishing", new BigDecimal("64"));
        stats.put("buildStageDuration:artifactsPublishing", new BigDecimal("1327"));
        
        SBuild build = buildWithStatistics(stats);
        mockBuildRunners(build, asList(runner("test_step_1", "Maven Test")));

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Should extract all 6 stages in execution order
        assertThat(steps).hasSize(6);
        assertThat(steps.get(0).getName()).isEqualTo("Checkout");
        assertThat(steps.get(1).getName()).isEqualTo("Update Tools");
        assertThat(steps.get(2).getName()).isEqualTo("Preparation");
        assertThat(steps.get(3).getName()).isEqualTo("Maven Test");
        assertThat(steps.get(4).getName()).isEqualTo("Finalize Build");
        assertThat(steps.get(5).getName()).isEqualTo("Publish Artifacts");
        
        // Verify timing is cumulative (each step starts where previous ended)
        assertThat(steps.get(0).getDurationMs()).isEqualTo(3908L);
        assertThat(steps.get(1).getDurationMs()).isEqualTo(1L);
        assertThat(steps.get(5).getDurationMs()).isEqualTo(1327L);
    }

    @Test
    public void shouldHandleMissingStepWhenBuildFails() {
        // Given: A failed build where step 2 never ran (no statistics entry)
        Map<String, BigDecimal> stats = new HashMap<>();
        stats.put("buildStageDuration:sourcesUpdate", new BigDecimal("3908"));
        stats.put("buildStageDuration:toolsUpdating", new BigDecimal("1"));
        stats.put("buildStageDuration:firstStepPreparation", new BigDecimal("1"));
        stats.put("buildStageDuration:buildSteptest_step_1", new BigDecimal("109"));
        // Note: buildSteptest_step_2 is MISSING (build failed before it ran)
        stats.put("buildStageDuration:buildFinishing", new BigDecimal("64"));
        stats.put("buildStageDuration:artifactsPublishing", new BigDecimal("1327"));
        
        SBuild build = buildWithStatistics(stats);
        mockBuildRunners(build, asList(
            runner("test_step_1", "test step 1"),
            runner("test_step_2", "test step 2") // Configured but didn't run
        ));

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Should only extract step 1, not step 2 (no statistics = didn't run)
        assertThat(steps).hasSize(6); // 5 lifecycle stages + 1 executed user step
        assertThat(steps.stream().filter(s -> s.getName().contains("test step")).count()).isEqualTo(1);
        assertThat(steps.stream().anyMatch(s -> s.getName().equals("test step 1"))).isTrue();
        assertThat(steps.stream().anyMatch(s -> s.getName().equals("test step 2"))).isFalse();
    }

    @Test
    public void shouldMarkAllStepsAsSuccessRegardlessOfBuildStatus() {
        // Given: A FAILED build (but we can't detect which step failed via API)
        Map<String, BigDecimal> stats = new HashMap<>();
        stats.put("buildStageDuration:buildSteptest_step_1", new BigDecimal("126"));
        
        SBuild build = buildWithStatistics(stats);
        when(build.getBuildStatus()).thenReturn(Status.FAILURE); // Build failed
        mockBuildRunners(build, asList(runner("test_step_1", "test step 1")));

        // When: Extracting build steps
        List<BuildStep> steps = processor.extractBuildSteps(build);

        // Then: Steps are still marked as SUCCESS (API limitation)
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(steps.get(0).getError()).isNull();
        // Note: Job-level status would be ERROR, but step-level status unknown
    }

    // Helper methods

    private SBuild buildWithStatistics(Map<String, BigDecimal> statistics) {
        SBuild build = new MockBuild.Builder(1, JOB)
                .withStatus(Status.NORMAL)
                .withStartDate(new Date(1000000))
                .withEndDate(new Date(1010000))
                .withFullName("Test Job")
                .build();
        
        when(build.getStatisticValues()).thenReturn(statistics);
        when(buildServer.getRootUrl()).thenReturn("http://localhost");
        
        // Mock build type with empty runners by default
        SBuildType buildType = mock(SBuildType.class);
        when(buildType.getBuildRunners()).thenReturn(emptyList());
        when(build.getBuildType()).thenReturn(buildType);
        
        return build;
    }

    private void mockBuildRunners(SBuild build, List<SBuildRunnerDescriptor> runners) {
        SBuildType buildType = build.getBuildType();
        when(buildType.getBuildRunners()).thenReturn(runners);
    }

    private SBuildRunnerDescriptor runner(String id, String name) {
        SBuildRunnerDescriptor runner = mock(SBuildRunnerDescriptor.class);
        when(runner.getId()).thenReturn(id);
        when(runner.getName()).thenReturn(name);
        return runner;
    }
}
