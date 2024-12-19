/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook.PipelineStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.ServerSettings;
import jetbrains.buildServer.util.EventDispatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.singletonList;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_QUEUE_TIME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_SERVER_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.IS_PARTIAL_RETRY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.LOCALHOST;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.NO_PARTIAL_RETRY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultErrorInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultGitInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultHostInfo;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.defaultUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DatadogServerAdapterProcessingTest {

    @Captor
    private ArgumentCaptor<List<Webhook>> webhooksCaptor;

    @Mock
    private ProjectHandler projectHandlerMock;
    @Mock
    private DatadogClient datadogClientMock;
    @Mock
    private SBuildServer buildServerMock;
    @Mock
    private BuildsManager buildsManagerMock;
    @Mock
    private EventDispatcher<BuildServerListener> eventListener;
    @Mock
    private GitInformationExtractor gitInfoExtractorMock;
    @Mock
    private ServerSettings serverSettings;

    private DatadogServerAdapter datadogServerAdapter;

    @Before
    public void setUp() {
        when(buildServerMock.getRootUrl()).thenReturn(LOCALHOST);
        when(gitInfoExtractorMock.extractGitInfo(any())).thenReturn(Optional.empty());
        when(serverSettings.getServerUUID()).thenReturn(DEFAULT_SERVER_ID);

        when(projectHandlerMock.getProjectParameters(any()))
            .thenReturn(new ProjectParameters(TEST_API_KEY, TEST_DD_SITE));
        when(projectHandlerMock.isPluginEnabled(any())).thenReturn(true);

        BuildChainProcessor chainProcessor = new BuildChainProcessor(buildServerMock, datadogClientMock, projectHandlerMock, gitInfoExtractorMock, serverSettings);
        datadogServerAdapter = new DatadogServerAdapter(eventListener, buildsManagerMock, chainProcessor, projectHandlerMock);
    }

    @Test
    public void shouldIgnoreJobBuilds() {
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB).build();

        datadogServerAdapter.buildFinished(jobBuild);

        verifyZeroInteractions(datadogClientMock, buildsManagerMock);
    }

    @Test
    public void shouldIgnoreBuildForProjectNotEnabled() {
        when(projectHandlerMock.isPluginEnabled(any())).thenReturn(false);
        SRunningBuild validBuild = new MockBuild.Builder(1, PIPELINE).build();

        datadogServerAdapter.buildFinished(validBuild);

        verifyZeroInteractions(datadogClientMock, buildsManagerMock);
    }

    @Test
    public void shouldIgnorePersonalBuilds() {
        SRunningBuild personalBuild = new MockBuild.Builder(1, PIPELINE).isPersonal().build();

        datadogServerAdapter.buildFinished(personalBuild);

        verifyZeroInteractions(datadogClientMock, buildsManagerMock);
    }

    @Test
    public void shouldIgnoreCompositeBuildsWithDependents() {
        SRunningBuild compositeBuildWithDependents = new MockBuild.Builder(1, PIPELINE)
            .withNumOfDependents(2)
            .build();

        datadogServerAdapter.buildFinished(compositeBuildWithDependents);

        verifyZeroInteractions(datadogClientMock, buildsManagerMock);
    }

    @Test
    public void shouldIgnoreBuildIfNotPresentInTeamcityServer() {
        int pipelineID = 1;
        SRunningBuild pipelineBuild = new MockBuild.Builder(pipelineID, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(pipelineID)).thenReturn(null);

        datadogServerAdapter.buildFinished(pipelineBuild);

        verifyZeroInteractions(datadogClientMock);
    }

    @Test
    public void shouldProcessPipelineBuildWithoutDependencies() {
        // Setup
        SRunningBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        PipelineWebhook expectedWebhook = new PipelineWebhook(
            DEFAULT_NAME,
            defaultUrl(pipelineBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-1",
            "1",
            NO_PARTIAL_RETRY,
            PipelineStatus.SUCCESS);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).containsExactly(expectedWebhook);
    }

    @Test
    public void shouldDetectAutomaticRetries() {
        // Setup
        SRunningBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).isTriggeredByRetry().build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        PipelineWebhook expectedWebhook = new PipelineWebhook(
            DEFAULT_NAME,
            defaultUrl(pipelineBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-1",
            "1",
            IS_PARTIAL_RETRY,
            PipelineStatus.SUCCESS);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).containsExactly(expectedWebhook);
    }

    @Test
    public void shouldProcessPipelineBuildWithOneDependency() {
        // Setup: [job -> pipeline]
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
            .withAllDependencies(singletonList(jobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-2",
                "2",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            new JobWebhook(
                DEFAULT_NAME,
                defaultUrl(jobBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-2",
                DEFAULT_NAME,
                "serverID-1",
                JobStatus.SUCCESS,
                DEFAULT_QUEUE_TIME));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldProcessPipelineBuildWithMultipleDependencies() {
        // Setup: [firstJob -> secondJob -> pipeline]
        SRunningBuild firstJobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild secondJobBuild = new MockBuild.Builder(2, JOB)
            .withDependencies(singletonList(firstJobBuild))
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE)
            .withAllDependencies(Arrays.asList(firstJobBuild, secondJobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(3)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        JobWebhook secondJobWebhook = new JobWebhook(
            DEFAULT_NAME,
            defaultUrl(secondJobBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-3",
            DEFAULT_NAME,
            "serverID-2",
            JobStatus.SUCCESS,
            DEFAULT_QUEUE_TIME);
        secondJobWebhook.setDependenciesIds(singletonList("serverID-1"));

        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                "3",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            new JobWebhook(
                DEFAULT_NAME,
                defaultUrl(firstJobBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                DEFAULT_NAME,
                "serverID-1",
                JobStatus.SUCCESS,
                DEFAULT_QUEUE_TIME),
            secondJobWebhook);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(3).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldNotSendWebhooksForJobsFromPreviousRetry() {
        // Setup: [firstJob (reused) -> secondJob -> pipeline]
        Date firstJobStart = new Date(1000);
        Date pipelineStart = new Date(5000);
        SRunningBuild firstJobBuild = new MockBuild.Builder(1, JOB)
            .withStartDate(firstJobStart)
            .build();
        SRunningBuild secondJobBuild = new MockBuild.Builder(2, JOB)
            .withStartDate(pipelineStart)
            .withDependencies(singletonList(firstJobBuild))
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE)
            .withStartDate(pipelineStart)
            .withAllDependencies(Arrays.asList(firstJobBuild, secondJobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(3)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        // First job should be removed as it started before the pipeline (accounting for 3s offset)
        JobWebhook secondJobWebhook = new JobWebhook(
            DEFAULT_NAME,
            defaultUrl(secondJobBuild),
            toRFC3339(pipelineStart),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-3",
            DEFAULT_NAME,
            "serverID-2",
            JobStatus.SUCCESS,
            3000);
        secondJobWebhook.setDependenciesIds(singletonList("serverID-1"));

        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(pipelineStart),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                "3",
                IS_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            secondJobWebhook);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldNotSendWebhooksForMiddleCompositeBuilds() {
        // Setup: [firstJob -> secondJob (composite) -> pipeline]
        SRunningBuild firstJobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild secondJobBuild = new MockBuild.Builder(2, JOB)
            .withAllDependencies(singletonList(firstJobBuild))
            .isComposite()
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE)
            .withAllDependencies(Arrays.asList(firstJobBuild, secondJobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(3)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        // Second job should be removed as the build is composite
        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                "3",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            new JobWebhook(
                DEFAULT_NAME,
                defaultUrl(firstJobBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                DEFAULT_NAME,
                "serverID-1",
                JobStatus.SUCCESS,
                DEFAULT_QUEUE_TIME));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldNotSendWebhooksForPersonalBuilds() {
        // Setup: [firstJob -> secondJob (personal) -> pipeline]
        SRunningBuild firstJobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild secondJobBuild = new MockBuild.Builder(2, JOB)
            .withAllDependencies(singletonList(firstJobBuild))
            .isPersonal()
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE)
            .withAllDependencies(Arrays.asList(firstJobBuild, secondJobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(3)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        // Second job should be removed as the build is personal
        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                "3",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            new JobWebhook(
                DEFAULT_NAME,
                defaultUrl(firstJobBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-3",
                DEFAULT_NAME,
                "serverID-1",
                JobStatus.SUCCESS,
                DEFAULT_QUEUE_TIME));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldSendWebhooksAccountingForStartOffset() {
        // Setup: [job -> pipeline]. The job started before the pipeline, but it is within the offset of 3s
        Date jobStart = new Date(4000);
        Date pipelineStart = new Date(5000);
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB)
            .withStartDate(jobStart)
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
            .withStartDate(pipelineStart)
            .withAllDependencies(singletonList(jobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(pipelineStart),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-2",
                "2",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            new JobWebhook(
                DEFAULT_NAME,
                defaultUrl(jobBuild),
                toRFC3339(jobStart),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-2",
                DEFAULT_NAME,
                "serverID-1",
                JobStatus.SUCCESS,
                2000));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

     @Test
    public void shouldSendJobWebhookWithAdditionalInformation() {
        // Setup: [job -> pipeline]
         SRunningBuild jobBuild = new MockBuild.Builder(1, JOB)
            .addNodeInformation()
            .withFailureReason(TC_FAILED_TESTS_TYPE)
            .build();
         SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
            .withAllDependencies(singletonList(jobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

         JobWebhook jobWebhook = new JobWebhook(
             DEFAULT_NAME,
             defaultUrl(jobBuild),
             toRFC3339(DEFAULT_START_DATE),
             toRFC3339(DEFAULT_END_DATE),
             "serverID-2",
             DEFAULT_NAME,
             "serverID-1",
             JobStatus.ERROR,
             DEFAULT_QUEUE_TIME);

         jobWebhook.setHostInfo(defaultHostInfo());
         jobWebhook.setErrorInfo(defaultErrorInfo());

         List<Webhook> expectedWebhooks = Arrays.asList(
            new PipelineWebhook(
                DEFAULT_NAME,
                defaultUrl(pipelineBuild),
                toRFC3339(DEFAULT_START_DATE),
                toRFC3339(DEFAULT_END_DATE),
                "serverID-2",
                "2",
                NO_PARTIAL_RETRY,
                PipelineStatus.SUCCESS),
            jobWebhook);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldSendWebhooksWithGitInformation() {
        // Setup: [job -> pipeline]
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB)
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
            .withAllDependencies(singletonList(jobBuild))
            .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);
        when(gitInfoExtractorMock.extractGitInfo(pipelineBuild)).thenReturn(Optional.of(defaultGitInfo()));

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        PipelineWebhook expectedPipelineWebhook = new PipelineWebhook(
            DEFAULT_NAME,
            defaultUrl(pipelineBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-2",
            "2",
            NO_PARTIAL_RETRY,
            PipelineStatus.SUCCESS);
        expectedPipelineWebhook.setGitInfo(defaultGitInfo());

        JobWebhook expectedJobWebhook = new JobWebhook(
            DEFAULT_NAME,
            defaultUrl(jobBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-2",
            DEFAULT_NAME,
            "serverID-1",
            JobStatus.SUCCESS,
            DEFAULT_QUEUE_TIME);
        expectedJobWebhook.setGitInfo(defaultGitInfo());

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).containsExactlyInAnyOrder(expectedPipelineWebhook, expectedJobWebhook);
    }

    @Test
    public void shouldSendWebhookWithTags() {
        // Setup
        List<String> tags = Arrays.asList("mytag1:myvalue1", "mytag2:myvalue2");
        SRunningBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).withTags(tags).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        PipelineWebhook expectedWebhook = new PipelineWebhook(
            DEFAULT_NAME,
            defaultUrl(pipelineBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-1",
            "1",
            NO_PARTIAL_RETRY,
            PipelineStatus.SUCCESS);

        expectedWebhook.setTags(tags);
        assertThat(webhooksCaptor.getValue()).containsExactly(expectedWebhook);
    }

    @Test
    public void shouldDetectCanceledPipeline() {
        // Setup: [job -> pipeline]
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB)
            .withEndDate(null)
            .build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
            .withCanceledInfo("canceled")
            .withAllDependencies(singletonList(jobBuild))
            .build();
        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        datadogServerAdapter.buildInterrupted(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
            .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        // Job webhook should not be sent as it has an invalid end date
        PipelineWebhook expectedWebhook = new PipelineWebhook(
            DEFAULT_NAME,
            defaultUrl(pipelineBuild),
            toRFC3339(DEFAULT_START_DATE),
            toRFC3339(DEFAULT_END_DATE),
            "serverID-2",
            "2",
            NO_PARTIAL_RETRY,
            PipelineStatus.CANCELED);

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).containsExactly(expectedWebhook);
    }

    @Test
    public void shouldGenerateEmptyURLsWhenInvalid() {
        // Setup: invalid server root URL
        when(buildServerMock.getRootUrl()).thenReturn("invalid//url");

        // Setup: [job -> pipeline]
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
                .withAllDependencies(singletonList(jobBuild))
                .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        BuildChainProcessor chainProcessor = new BuildChainProcessor(buildServerMock, datadogClientMock, projectHandlerMock, gitInfoExtractorMock, serverSettings);
        datadogServerAdapter = new DatadogServerAdapter(eventListener, buildsManagerMock, chainProcessor, projectHandlerMock);
        datadogServerAdapter.buildFinished(pipelineBuild);
        String emptyUrl = "";

        // Then
        verify(datadogClientMock, times(1))
                .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        List<Webhook> expectedWebhooks = Arrays.asList(
                new PipelineWebhook(
                        DEFAULT_NAME,
                        emptyUrl,
                        toRFC3339(DEFAULT_START_DATE),
                        toRFC3339(DEFAULT_END_DATE),
                        "serverID-2",
                        "2",
                        NO_PARTIAL_RETRY,
                        PipelineStatus.SUCCESS),
                new JobWebhook(
                        DEFAULT_NAME,
                        emptyUrl,
                        toRFC3339(DEFAULT_START_DATE),
                        toRFC3339(DEFAULT_END_DATE),
                        "serverID-2",
                        DEFAULT_NAME,
                        "serverID-1",
                        JobStatus.SUCCESS,
                        DEFAULT_QUEUE_TIME));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }

    @Test
    public void shouldGenerateValidURLs() {
        // Setup: server root URL with final slash
        when(buildServerMock.getRootUrl()).thenReturn("http://localhost/");

        // Setup: [job -> pipeline]
        SRunningBuild jobBuild = new MockBuild.Builder(1, JOB).build();
        SRunningBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE)
                .withAllDependencies(singletonList(jobBuild))
                .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineBuild);

        // When
        BuildChainProcessor chainProcessor = new BuildChainProcessor(buildServerMock, datadogClientMock, projectHandlerMock, gitInfoExtractorMock, serverSettings);
        datadogServerAdapter = new DatadogServerAdapter(eventListener, buildsManagerMock, chainProcessor, projectHandlerMock);
        datadogServerAdapter.buildFinished(pipelineBuild);

        // Then
        verify(datadogClientMock, times(1))
                .sendWebhooksAsync(webhooksCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        List<Webhook> expectedWebhooks = Arrays.asList(
                new PipelineWebhook(
                        DEFAULT_NAME,
                        defaultUrl(pipelineBuild),
                        toRFC3339(DEFAULT_START_DATE),
                        toRFC3339(DEFAULT_END_DATE),
                        "serverID-2",
                        "2",
                        NO_PARTIAL_RETRY,
                        PipelineStatus.SUCCESS),
                new JobWebhook(
                        DEFAULT_NAME,
                        defaultUrl(jobBuild),
                        toRFC3339(DEFAULT_START_DATE),
                        toRFC3339(DEFAULT_END_DATE),
                        "serverID-2",
                        DEFAULT_NAME,
                        "serverID-1",
                        JobStatus.SUCCESS,
                        DEFAULT_QUEUE_TIME));

        List<Webhook> webhooksSent = webhooksCaptor.getValue();
        assertThat(webhooksSent).hasSize(2).hasSameElementsAs(expectedWebhooks);
    }
}
