package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static java.util.Collections.singletonList;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_CHECKOUT_DIR;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_COMMIT_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_FAILURE_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_NODE_HOSTNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_NODE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_PROJECT_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_QUEUE_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_REPO_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain.PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class DatadogNotificatorProcessingTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final String TEST_DD_SITE = "test-dd-site";

    private DatadogClient datadogClientMock;
    private ProjectHandler projectHandlerMock;
    private BuildsManager buildsManagerMock;
    private BuildDependenciesManager dependenciesManagerMock;
    private SBuildServer buildServerMock;

    private CIEntityFactory entityCreator;
    private DatadogNotificator notificator;

    @Before
    public void setUp() {
        datadogClientMock = mock(DatadogClient.class);
        projectHandlerMock = mock(ProjectHandler.class);
        buildsManagerMock = mock(BuildsManager.class);
        dependenciesManagerMock = mock(BuildDependenciesManager.class);
        buildServerMock = mock(SBuildServer.class);

        when(buildServerMock.getRootUrl()).thenReturn("root-url");
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(TEST_API_KEY, TEST_DD_SITE));

        entityCreator = new CIEntityFactory(buildServerMock, dependenciesManagerMock);
        notificator = new DatadogNotificator(mock(NotificatorRegistry.class), buildsManagerMock,
                datadogClientMock, projectHandlerMock, entityCreator);
    }

    @Test
    public void shouldIgnoreNullBuilds() {
        // Setup
        SBuild mockSBuild = new MockBuild.Builder(1, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(null);

        notificator.onFinishedBuild(mockSBuild);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, dependenciesManagerMock, buildServerMock);
    }

    @Test
    public void shouldIgnoreCompositeBuildWithDependents() {
        SBuild compositeBuildMock = new MockBuild.Builder(1, PIPELINE).withNumOfDependents(1).build();

        notificator.onFinishedBuild(compositeBuildMock);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, dependenciesManagerMock, buildServerMock);
    }

    @Test
    public void shouldSendWebhookForPipelineBuild() {
        // Setup
        SBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        notificator.onFinishedBuild(pipelineBuild);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Pipeline pipelineWebhook = pipelineCaptor.getValue();
        assertThat(pipelineWebhook.uniqueId()).isEqualTo(String.valueOf(1));
        assertThat(pipelineWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(pipelineWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(pipelineWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(pipelineWebhook.url()).isEqualTo("root-url/build/1");

        assertThat(pipelineWebhook.isPartialRetry()).isFalse();
        assertThat(pipelineWebhook.previousAttempt()).isNull();
        assertThat(pipelineWebhook.gitInfo()).isNull();
    }

    @Test
    public void shouldSendWebhookForJobBuild() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB).withNumOfDependents(3).build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));
        assertThat(jobWebhook.pipelineID()).isEqualTo(String.valueOf(2));
        assertThat(jobWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(jobWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(jobWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(jobWebhook.url()).isEqualTo("root-url/build/1");
        assertThat(jobWebhook.queueTimeMs()).isEqualTo(DEFAULT_START_DATE.getTime() - DEFAULT_QUEUE_DATE.getTime());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForJobWithoutPipeline() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB).withNumOfDependents(3).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.empty());

        // When
        notificator.onFinishedBuild(jobBuild);
    }

    @Test
    public void shouldHandleAutomaticRetries() {
        // Setup
        SFinishedBuild prevAttempt = new MockBuild.Builder(1, PIPELINE).buildFinished();
        SBuild pipelineRetry = new MockBuild.Builder(2, PIPELINE)
                .isTriggeredByReply().withPreviousAttempt(prevAttempt).build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineRetry);

        // When
        notificator.onFinishedBuild(pipelineRetry);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        assertThat(pipelineCaptor.getValue().uniqueId()).isEqualTo(String.valueOf(2));
        assertThat(pipelineCaptor.getValue().isPartialRetry()).isTrue();
        assertThat(pipelineCaptor.getValue().previousAttempt()).isNotNull();
        assertThat(pipelineCaptor.getValue().previousAttempt().id()).isEqualTo(buildID(prevAttempt));
    }

    @Test
    public void shouldSendWebhookWithGitInformation() {
        // Setup
        SBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).addGitInformation().build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        notificator.onFinishedBuild(pipelineBuild);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Pipeline pipelineWebhook = pipelineCaptor.getValue();
        assertThat(pipelineWebhook.uniqueId()).isEqualTo(String.valueOf(1));

        GitInfo gitInfo = pipelineWebhook.gitInfo();
        assertThat(gitInfo).isNotNull();
        assertThat(gitInfo.sha()).isEqualTo(DEFAULT_COMMIT_SHA);
        assertThat(gitInfo.authorTime()).isEqualTo(toRFC3339(DEFAULT_COMMIT_DATE));
        assertThat(gitInfo.commitTime()).isEqualTo(toRFC3339(DEFAULT_COMMIT_DATE));
        assertThat(gitInfo.committerName()).isEqualTo(DEFAULT_COMMIT_USERNAME);
        assertThat(gitInfo.committerEmail()).isEqualTo(DEFAULT_COMMIT_USERNAME);
        assertThat(gitInfo.branch()).isEqualTo(DEFAULT_BRANCH);
        assertThat(gitInfo.defaultBranch()).isEqualTo(DEFAULT_BRANCH);
        assertThat(gitInfo.repositoryURL()).isEqualTo(DEFAULT_REPO_URL);
    }

    @Test
    public void shouldRetrieveGitInformationFromPreviousBuilds() {
        // Setup
        SFinishedBuild firstAttempt = new MockBuild.Builder(1, PIPELINE)
                .addGitInformation().buildFinished();
        SFinishedBuild secondAttempt = new MockBuild.Builder(2, PIPELINE)
                .isTriggeredByReply().withPreviousAttempt(firstAttempt).buildFinished();
        SBuild thirdAttempt = new MockBuild.Builder(3, PIPELINE)
                .isTriggeredByReply().withPreviousAttempt(secondAttempt).build();

        when(buildsManagerMock.findBuildInstanceById(3)).thenReturn(thirdAttempt);

        // When
        notificator.onFinishedBuild(thirdAttempt);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Pipeline pipelineWebhook = pipelineCaptor.getValue();
        assertThat(pipelineWebhook.uniqueId()).isEqualTo(String.valueOf(3));

        // Git information should be present because it's taken from first attempt
        GitInfo gitInfo = pipelineWebhook.gitInfo();
        assertThat(gitInfo).isNotNull();
        assertThat(gitInfo.sha()).isEqualTo(DEFAULT_COMMIT_SHA);
        assertThat(gitInfo.authorTime()).isEqualTo(toRFC3339(DEFAULT_COMMIT_DATE));
        assertThat(gitInfo.commitTime()).isEqualTo(toRFC3339(DEFAULT_COMMIT_DATE));
        assertThat(gitInfo.committerName()).isEqualTo(DEFAULT_COMMIT_USERNAME);
        assertThat(gitInfo.committerEmail()).isEqualTo(DEFAULT_COMMIT_USERNAME);
        assertThat(gitInfo.branch()).isEqualTo(DEFAULT_BRANCH);
        assertThat(gitInfo.defaultBranch()).isEqualTo(DEFAULT_BRANCH);
        assertThat(gitInfo.repositoryURL()).isEqualTo(DEFAULT_REPO_URL);
    }

    @Test
    public void shouldSendWebhookWithNodeInformation() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB).addNodeInformation().build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));

        Job.HostInfo hostInfo = jobWebhook.hostInfo();
        assertThat(hostInfo).isNotNull();
        assertThat(hostInfo.hostname()).isEqualTo(DEFAULT_NODE_HOSTNAME);
        assertThat(hostInfo.name()).isEqualTo(DEFAULT_NODE_NAME);
        assertThat(hostInfo.workspace()).isEqualTo(DEFAULT_CHECKOUT_DIR);
    }

    @Test
    public void shouldSendWebhookWithFailureReason() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withStatus(Status.FAILURE).withFailureReason(TC_FAILED_TESTS_TYPE).build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));

        Job.ErrorInfo errorInfo = jobWebhook.errorInfo();
        assertThat(errorInfo).isNotNull();
        assertThat(errorInfo.message()).isEqualTo(DEFAULT_FAILURE_MESSAGE);
        assertThat(errorInfo.type()).isEqualTo("Tests Failed");
        assertThat(errorInfo.domain()).isEqualTo(PROVIDER);
    }

    @Test
    public void shouldNotIncludeFailureReasonForUnsupportedTypes() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withStatus(Status.FAILURE).withFailureReason("Unsupported type").build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));

        Job.ErrorInfo errorInfo = jobWebhook.errorInfo();
        assertThat(errorInfo).isNull();
    }

    @Test
    public void shouldIncludeDependenciesForJob() {
        // Setup
        SBuild jobCompile = new MockBuild.Builder(1, JOB).build();
        SBuild jobTest = new MockBuild.Builder(2, JOB)
                .withDependencies(singletonList(jobCompile))
                .build();
        SBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(jobTest);
        when(dependenciesManagerMock.getPipelineBuild(jobTest)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobTest);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(2));
        assertThat(jobWebhook.dependencies()).isEqualTo(singletonList("1"));
    }
}
