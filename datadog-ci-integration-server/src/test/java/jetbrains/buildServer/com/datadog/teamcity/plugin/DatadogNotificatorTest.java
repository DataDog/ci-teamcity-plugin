package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.*;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildUtils.toRFC3339;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class DatadogNotificatorTest {

    private DatadogClient datadogClientMock;
    private ProjectHandler projectHandlerMock;
    private BuildsManager buildsManagerMock;
    private SBuildServer buildServerMock;
    private BuildDependenciesManager dependenciesManagerMock;

    private DatadogNotificator notificator;

    @Before
    public void setUp() {
        datadogClientMock = mock(DatadogClient.class);
        projectHandlerMock = mock(ProjectHandler.class);
        buildsManagerMock = mock(BuildsManager.class);
        buildServerMock = mock(SBuildServer.class);
        dependenciesManagerMock = mock(BuildDependenciesManager.class);

        when(buildServerMock.getRootUrl()).thenReturn("root-url");
        notificator = new DatadogNotificator(mock(NotificatorRegistry.class), buildsManagerMock,
                datadogClientMock, projectHandlerMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldIgnoreNullBuilds() {
        // Setup
        SBuild mockSBuild = new MockBuild.Builder(1).isComposite().build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(null);

        notificator.onFinishedBuild(mockSBuild);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldIgnoreCompositeBuildWithDependents() {
        SBuild compositeBuildMock = new MockBuild.Builder(1).isComposite().withNumOfDependents(1).build();

        notificator.onFinishedBuild(compositeBuildMock);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldSendWebhookForPipelineBuild() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild pipelineBuild = new MockBuild.Builder(1).isComposite().build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);

        // When
        notificator.onFinishedBuild(pipelineBuild);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(mockApiKey), eq(mockSite));

        Pipeline pipelineWebhook = pipelineCaptor.getValue();
        assertThat(pipelineWebhook.uniqueId()).isEqualTo(String.valueOf(1));
        assertThat(pipelineWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(pipelineWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(pipelineWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(pipelineWebhook.url()).isEqualTo("root-url/build/1");
        assertThat(pipelineWebhook.isPartialRetry()).isFalse();
        assertThat(pipelineWebhook.previousAttempt()).isNull();
    }

    @Test
    public void shouldSendWebhookForJobBuild() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild jobBuild = new MockBuild.Builder(1).withNumOfDependents(3).build();
        SBuild pipelineBuild =new MockBuild.Builder(2).isComposite().build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(mockApiKey), eq(mockSite));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));
        assertThat(jobWebhook.pipelineID()).isEqualTo(String.valueOf(2));
        assertThat(jobWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(jobWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(jobWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(jobWebhook.url()).isEqualTo("root-url/build/1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForJobWithoutPipeline() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild jobBuild = new MockBuild.Builder(1).withNumOfDependents(3).build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.empty());

        // When
        notificator.onFinishedBuild(jobBuild);
    }

    @Test
    public void shouldHandleManualRetries() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SFinishedBuild prevAttempt = new MockBuild.Builder(1).isComposite().buildFinished();
        SBuild pipelineRetry = new MockBuild.Builder(2)
                .isComposite().isTriggeredByUser().withPreviousAttempt(prevAttempt).build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineRetry);

        // When
        notificator.onFinishedBuild(pipelineRetry);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(mockApiKey), eq(mockSite));

        assertThat(pipelineCaptor.getValue().uniqueId()).isEqualTo(String.valueOf(2));
        assertThat(pipelineCaptor.getValue().isPartialRetry()).isTrue();
        assertThat(pipelineCaptor.getValue().previousAttempt()).isNotNull();
        assertThat(pipelineCaptor.getValue().previousAttempt().id()).isEqualTo(buildID(prevAttempt));
    }

    @Test
    public void shouldHandleAutomaticRetries() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SFinishedBuild prevAttempt = new MockBuild.Builder(1).isComposite().buildFinished();
        SBuild pipelineRetry = new MockBuild.Builder(2)
                .isComposite().isTriggeredByReply().withPreviousAttempt(prevAttempt).build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(pipelineRetry);

        // When
        notificator.onFinishedBuild(pipelineRetry);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(mockApiKey), eq(mockSite));

        assertThat(pipelineCaptor.getValue().uniqueId()).isEqualTo(String.valueOf(2));
        assertThat(pipelineCaptor.getValue().isPartialRetry()).isTrue();
        assertThat(pipelineCaptor.getValue().previousAttempt()).isNotNull();
        assertThat(pipelineCaptor.getValue().previousAttempt().id()).isEqualTo(buildID(prevAttempt));
    }
}
