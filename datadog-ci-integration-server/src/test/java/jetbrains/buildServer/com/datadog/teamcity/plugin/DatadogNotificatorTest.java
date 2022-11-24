package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildDependenciesManager;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.mockSBuild;
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

        notificator = new DatadogNotificator(mock(NotificatorRegistry.class), buildsManagerMock,
                datadogClientMock, projectHandlerMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldIgnoreNullBuilds() {
        // Setup
        SBuild mockSBuild = mockSBuild(1, true, 0);
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(null);

        notificator.onFinishedBuild(mockSBuild);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldIgnoreCompositeBuildWithDependents() {
        SBuild compositeBuildMock = mockSBuild(1, true, 1);

        notificator.onFinishedBuild(compositeBuildMock);

        verifyZeroInteractions(projectHandlerMock, datadogClientMock, buildServerMock, dependenciesManagerMock);
    }

    @Test
    public void shouldSendWebhookForPipelineBuild() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of("Project ID")))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild pipelineBuild = mockSBuild(1, true, 0);
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(pipelineBuild);
        when(buildServerMock.getRootUrl()).thenReturn("root-url");

        // When
        notificator.onFinishedBuild(pipelineBuild);

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(mockApiKey), eq(mockSite));

        assertThat(pipelineCaptor.getValue().uniqueId()).isEqualTo(String.valueOf(1));
    }

    @Test
    public void shouldSendWebhookForJobBuild() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of("Project ID")))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild jobBuild = mockSBuild(1, false, 3);
        SBuild pipelineBuild = mockSBuild(2, true, 0);

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(buildServerMock.getRootUrl()).thenReturn("root-url");
        when(dependenciesManagerMock.getPipelineBuildForJob(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        notificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(mockApiKey), eq(mockSite));

        assertThat(jobCaptor.getValue().id()).isEqualTo(String.valueOf(1));
        assertThat(jobCaptor.getValue().pipelineID()).isEqualTo(String.valueOf(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForJobWithoutPipeline() {
        // Setup
        String mockApiKey = "test-api-key";
        String mockSite = "test-dd-site";
        when(projectHandlerMock.getProjectParameters(Optional.of("Project ID")))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));

        SBuild jobBuild = mockSBuild(1, false, 3);
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(buildServerMock.getRootUrl()).thenReturn("root-url");
        when(dependenciesManagerMock.getPipelineBuildForJob(jobBuild)).thenReturn(Optional.empty());

        // When
        notificator.onFinishedBuild(jobBuild);
    }
}
