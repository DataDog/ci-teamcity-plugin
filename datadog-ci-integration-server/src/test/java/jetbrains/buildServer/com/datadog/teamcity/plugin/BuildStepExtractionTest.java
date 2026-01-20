/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.BuildStep;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.BuildStep.StepStatus;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.List;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-driven development for build step extraction.
 * 
 * Research TODO:
 * - Investigate TeamCity API: jetbrains.buildServer.serverSide.build.steps
 * - Check SBuild methods for step information
 * - Look at BuildStatistics for step timing data
 * - Explore build messages for step events
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildStepExtractionTest {

    private BuildChainProcessor processor;

    @Before
    public void setUp() {
        // TODO: Set up BuildChainProcessor with mocked dependencies
        // This will be implemented once we understand the TeamCity API
    }

    @Test
    public void shouldReturnEmptyListWhenNoStepsAvailable() {
        // Given: A build with no step information
        SBuild build = new MockBuild.Builder(1, JOB).build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Should return empty list
        assertThat(steps).isEmpty();
    }

    @Test
    public void shouldExtractSingleBuildStep() {
        // Given: A build with one step
        SBuild build = new MockBuild.Builder(1, JOB)
                .withSingleStep("Checkout", 1000L, 16000L, StepStatus.SUCCESS)
                .build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Should extract one step with correct timing
        assertThat(steps).hasSize(1);
        BuildStep step = steps.get(0);
        assertThat(step.getName()).isEqualTo("Checkout");
        assertThat(step.getDurationMs()).isEqualTo(15000L); // 16000 - 1000
        assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(step.getError()).isNull();
    }

    @Test
    public void shouldExtractMultipleBuildSteps() {
        // Given: A build with multiple steps
        SBuild build = new MockBuild.Builder(1, JOB)
                .withStep("Checkout", 1000L, 16000L, StepStatus.SUCCESS)
                .withStep("Maven compile", 16000L, 151000L, StepStatus.SUCCESS)
                .withStep("Maven test", 151000L, 180000L, StepStatus.SUCCESS)
                .build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Should extract all steps in order
        assertThat(steps).hasSize(3);
        
        assertThat(steps.get(0).getName()).isEqualTo("Checkout");
        assertThat(steps.get(0).getDurationMs()).isEqualTo(15000L);
        
        assertThat(steps.get(1).getName()).isEqualTo("Maven compile");
        assertThat(steps.get(1).getDurationMs()).isEqualTo(135000L);
        
        assertThat(steps.get(2).getName()).isEqualTo("Maven test");
        assertThat(steps.get(2).getDurationMs()).isEqualTo(29000L);
    }

    @Test
    public void shouldHandleFailedStep() {
        // Given: A build with a failed step
        SBuild build = new MockBuild.Builder(1, JOB)
                .withStep("Checkout", 1000L, 16000L, StepStatus.SUCCESS)
                .withStep("Maven test", 16000L, 45000L, StepStatus.ERROR, "Tests failed: 3 failures")
                .build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Should extract failed step with error message
        assertThat(steps).hasSize(2);
        
        BuildStep failedStep = steps.get(1);
        assertThat(failedStep.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(failedStep.getError()).isEqualTo("Tests failed: 3 failures");
    }

    @Test
    public void shouldHandleCanceledStep() {
        // Given: A build with a canceled step
        SBuild build = new MockBuild.Builder(1, JOB)
                .withStep("Checkout", 1000L, 16000L, StepStatus.SUCCESS)
                .withStep("Long running task", 16000L, 20000L, StepStatus.CANCELED)
                .build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Should mark step as canceled
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).getStatus()).isEqualTo(StepStatus.CANCELED);
    }

    @Test
    public void shouldFormatTimestampsAsRFC3339() {
        // Given: A build with steps
        SBuild build = new MockBuild.Builder(1, JOB)
                .withSingleStep("Checkout", 1000L, 16000L, StepStatus.SUCCESS)
                .build();

        // When: Extracting build steps
        List<BuildStep> steps = extractBuildSteps(build);

        // Then: Timestamps should be in RFC3339 format
        BuildStep step = steps.get(0);
        assertThat(step.getStart()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(step.getEnd()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    /**
     * Helper method to extract build steps.
     * This will be replaced with actual implementation once TeamCity API is understood.
     */
    private List<BuildStep> extractBuildSteps(SBuild build) {
        // TODO: Implement actual extraction logic once we research TeamCity API
        // For now, this is a placeholder that will be implemented in BuildChainProcessor
        throw new UnsupportedOperationException(
                "Build step extraction not yet implemented. " +
                "Need to research TeamCity API: jetbrains.buildServer.serverSide.build.steps");
    }
}
