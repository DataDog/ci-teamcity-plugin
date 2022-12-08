package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.runners.MockitoJUnitRunner;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REPO_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline.PipelineStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DatadogNotificatorPipelineProcessingTest extends BaseNotificatorProcessingTest {

    @Test
    public void shouldIgnoreCompositeBuildWithDependents() {
        SBuild compositeBuildMock = new MockBuild.Builder(1, PIPELINE)
                .withNumOfDependents(1)
                .build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(compositeBuildMock);

        datadogNotificator.onFinishedBuild(compositeBuildMock);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, dependenciesManagerMock, buildServerMock);
    }

    @Test
    public void shouldIgnoreNullBuilds() {
        SBuild mockSBuild = new MockBuild.Builder(1, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(null);

        datadogNotificator.onFinishedBuild(mockSBuild);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, dependenciesManagerMock, buildServerMock);
    }

    @Test
    public void shouldSendWebhookForStandardPipeline() {
        // Setup
        SBuild pipelineBuild = new MockBuild.Builder(1, PIPELINE).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        datadogNotificator.onFinishedBuild(pipelineBuild);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Pipeline pipelineWebhook = pipelineCaptor.getValue();
        assertThat(pipelineWebhook.uniqueId()).isEqualTo(String.valueOf(1));
        assertThat(pipelineWebhook.pipelineId()).isEqualTo(String.valueOf(1));

        assertThat(pipelineWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(pipelineWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(pipelineWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(pipelineWebhook.url()).isEqualTo("root-url/build/1");

        assertThat(pipelineWebhook.isPartialRetry()).isFalse();
        assertThat(pipelineWebhook.previousAttempt()).isNull();
        assertThat(pipelineWebhook.gitInfo()).isNull();
        assertThat(pipelineWebhook.status()).isEqualTo(SUCCESS);
    }

    @Test
    public void shouldHandleAutomaticBuildRetries() {
        // Setup
        SFinishedBuild prevAttempt = new MockBuild.Builder(1, PIPELINE).buildFinished();
        SBuild pipelineRetry = new MockBuild.Builder(2, PIPELINE)
                .isTriggeredByReply()
                .withPreviousAttempt(prevAttempt)
                .build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineRetry);

        // When
        datadogNotificator.onFinishedBuild(pipelineRetry);

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
        datadogNotificator.onFinishedBuild(pipelineBuild);

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
        datadogNotificator.onFinishedBuild(thirdAttempt);

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
}
