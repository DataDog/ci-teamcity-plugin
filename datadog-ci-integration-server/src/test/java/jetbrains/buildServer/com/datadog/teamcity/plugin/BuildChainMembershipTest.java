/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.ServerSettings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_SERVER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for the chain membership algorithm that determines which builds
 * are part of a triggered build chain based on snapshot dependencies.
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildChainMembershipTest {

    @Mock
    private SBuildServer buildServer;
    
    @Mock
    private DatadogClient datadogClient;
    
    @Mock
    private ProjectHandler projectHandler;
    
    @Mock
    private GitInformationExtractor gitInformationExtractor;
    
    @Mock
    private ServerSettings serverSettings;
    
    private BuildChainProcessor buildChainProcessor;

    @Before
    public void setUp() {
        when(serverSettings.getServerUUID()).thenReturn(DEFAULT_SERVER_ID);
        when(buildServer.getRootUrl()).thenReturn("http://localhost:8111");
        
        buildChainProcessor = new BuildChainProcessor(
            buildServer,
            datadogClient,
            projectHandler,
            gitInformationExtractor,
            serverSettings
        );
    }

    @Test
    public void shouldAcceptDependencyTriggeredByPipeline() {
        // Given: A pipeline build with one dependency triggered by snapshot dependency
        long pipelineId = 100L;
        long jobId = 99L;
        
        SRunningBuild job = new MockBuild.Builder(jobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(job))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should have 1 pipeline webhook + 1 job webhook
        assertThat(webhooks).hasSize(2);
        assertThat(webhooks.get(0)).isInstanceOf(PipelineWebhook.class);
        assertThat(webhooks.get(1)).isNotInstanceOf(PipelineWebhook.class); // JobWebhook
    }

    @Test
    public void shouldRejectDependencyTriggeredBySchedule() {
        // Given: A pipeline with a re-used dependency from a previous scheduled run
        long pipelineId = 100L;
        long reusedJobId = 50L; // Old build from earlier
        
        SRunningBuild reusedJob = new MockBuild.Builder(reusedJobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySchedule() // Not triggered by this pipeline
            .withStartDate(hoursAgo(8)) // Started 8 hours ago
            .withEndDate(hoursAgo(8))
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withStartDate(minutesAgo(5))
            .withEndDate(minutesAgo(2))
            .withAllDependencies(Arrays.asList(reusedJob))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should only have 1 pipeline webhook (re-used job excluded)
        assertThat(webhooks).hasSize(1);
        assertThat(webhooks.get(0)).isInstanceOf(PipelineWebhook.class);
    }

    @Test
    public void shouldHandleDiamondDependencyGraph() {
        // Given: Diamond dependency graph
        //        A (pipeline)
        //       / \
        //      B   C
        //       \ /
        //        D
        long pipelineId = 100L;
        long jobBId = 99L;
        long jobCId = 98L;
        long jobDId = 97L;
        
        SRunningBuild jobD = new MockBuild.Builder(jobDId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(jobBId) // Triggered by B
            .build();
        
        SRunningBuild jobB = new MockBuild.Builder(jobBId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .withAllDependencies(Arrays.asList(jobD))
            .build();
        
        SRunningBuild jobC = new MockBuild.Builder(jobCId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .withAllDependencies(Arrays.asList(jobD))
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(jobB, jobC, jobD))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should have 1 pipeline + 3 job webhooks (B, C, D all accepted)
        assertThat(webhooks).hasSize(4);
        assertThat(webhooks.get(0)).isInstanceOf(PipelineWebhook.class);
    }

    @Test
    public void shouldComputeWholeChainTiming() {
        // Given: A pipeline with dependencies that start before and end after the pipeline
        long pipelineId = 100L;
        long jobId = 99L;
        
        Date jobStartTime = hoursAgo(2);
        Date jobEndTime = minutesAgo(5); // Job finishes AFTER pipeline (pipeline ends at 28 min ago)
        Date pipelineStartTime = minutesAgo(30);
        Date pipelineEndTime = minutesAgo(28);
        
        SRunningBuild job = new MockBuild.Builder(jobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .withStartDate(jobStartTime)
            .withEndDate(jobEndTime)
            .withQueueDate(hoursAgo(3))
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withStartDate(pipelineStartTime)
            .withEndDate(pipelineEndTime)
            .withAllDependencies(Arrays.asList(job))
            .build();
        
        // When
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Pipeline webhook should span from job start to job end
        assertThat(webhooks).hasSize(2);
        Webhook pipelineWebhook = webhooks.get(0);
        String webhookStr = pipelineWebhook.toString();
        assertThat(webhookStr).contains("start='" + toRFC3339(jobStartTime) + "'");
        assertThat(webhookStr).contains("end='" + toRFC3339(jobEndTime) + "'");
    }

    @Test
    public void shouldHandleMultiLevelDependencies() {
        // Given: Three-level dependency chain A -> B -> C
        long pipelineId = 100L;
        long jobBId = 99L;
        long jobCId = 98L;
        
        SRunningBuild jobC = new MockBuild.Builder(jobCId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(jobBId) // Triggered by B
            .build();
        
        SRunningBuild jobB = new MockBuild.Builder(jobBId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId) // Triggered by pipeline
            .withAllDependencies(Arrays.asList(jobC))
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(jobB, jobC))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should accept all three levels (pipeline + B + C)
        assertThat(webhooks).hasSize(3);
    }

    @Test
    public void shouldExcludePersonalBuilds() {
        // Given: A pipeline with a personal build dependency
        long pipelineId = 100L;
        long personalJobId = 99L;
        
        SRunningBuild personalJob = new MockBuild.Builder(personalJobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .isPersonal() // Personal builds should be excluded
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(personalJob))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should only have pipeline webhook (personal job excluded)
        assertThat(webhooks).hasSize(1);
        assertThat(webhooks.get(0)).isInstanceOf(PipelineWebhook.class);
    }

    @Test
    public void shouldExcludeBuildsWithoutFinishDate() {
        // Given: A pipeline with a dependency that was canceled before it started
        long pipelineId = 100L;
        long canceledJobId = 99L;
        
        SRunningBuild canceledJob = new MockBuild.Builder(canceledJobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .withEndDate(null) // No finish date
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(canceledJob))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should only have pipeline webhook (canceled job excluded)
        assertThat(webhooks).hasSize(1);
        assertThat(webhooks.get(0)).isInstanceOf(PipelineWebhook.class);
    }

    @Test
    public void shouldHandleMixedAcceptedAndRejectedDependencies() {
        // Given: A pipeline with both accepted and rejected dependencies
        long pipelineId = 100L;
        long acceptedJobId = 99L;
        long reusedJobId = 50L;
        
        SRunningBuild acceptedJob = new MockBuild.Builder(acceptedJobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySnapshotDependency(pipelineId)
            .build();
        
        SRunningBuild reusedJob = new MockBuild.Builder(reusedJobId, JOB)
            .withStatus(Status.NORMAL)
            .isTriggeredBySchedule() // From previous run
            .build();
        
        SRunningBuild pipeline = new MockBuild.Builder(pipelineId, PIPELINE)
            .withStatus(Status.NORMAL)
            .isTriggeredByUser()
            .withAllDependencies(Arrays.asList(acceptedJob, reusedJob))
            .build();
        
        // When: Processing the pipeline
        List<Webhook> webhooks = buildChainProcessor.createWebhooks(pipeline);
        
        // Then: Should have 1 pipeline + 1 accepted job (reused job excluded)
        assertThat(webhooks).hasSize(2);
    }

    // Helper methods for date manipulation
    private Date hoursAgo(int hours) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, -hours);
        return cal.getTime();
    }

    private Date minutesAgo(int minutes) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, -minutes);
        return cal.getTime();
    }
}
