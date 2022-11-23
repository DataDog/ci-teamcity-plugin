package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.DatadogBuild;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.Pipeline;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Date;
import java.util.Optional;

import static java.time.Instant.now;
import static jetbrains.buildServer.messages.Status.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class DatadogNotificatorTest {

    private SBuildServer buildServerMock;
    private DatadogClient datadogClientMock;
    private ProjectHandler projectHandlerMock;

    private DatadogNotificator notificator;

    @Before
    public void setUp() {
        buildServerMock = mock(SBuildServer.class);
        datadogClientMock = mock(DatadogClient.class);
        projectHandlerMock = mock(ProjectHandler.class);

        notificator = new DatadogNotificator(mock(NotificatorRegistry.class), mock(BuildsManager.class),
                buildServerMock, datadogClientMock, projectHandlerMock);
    }

    @Test
    public void shouldSendWebhookForPipelineBuild() {
        // Setup
        String mockApiKey = "mock-api-key";
        String mockSite = "mock-dd-site";

        when(projectHandlerMock.getProjectParameters(Optional.of("Project")))
                .thenReturn(new ProjectParameters(mockApiKey, mockSite));
        when(buildServerMock.getRootUrl()).thenReturn("mock-url");

        // When
        long buildID = 100;
        notificator.onFinishedBuild(pipelineBuild(buildID));

        // Then
        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(pipelineCaptor.capture(), eq(mockApiKey), eq(mockSite));

        assertThat(pipelineCaptor.getValue().uniqueId()).isEqualTo(String.valueOf(buildID));
    }

    @Test
    public void shouldNotSendWebhookForCompositeBuildWithDependents() {
        // Setup
        when(projectHandlerMock.getProjectParameters(Optional.of("Project")))
            .thenReturn(new ProjectParameters("mock-api-key", "mock-dd-site"));
        when(buildServerMock.getRootUrl()).thenReturn("mock-url");

        // When
        DatadogBuild compositeBuild = compositeBuildWithDependents(100);
        notificator.onFinishedBuild(compositeBuild);

        // Then: at the moment we don't process composite builds if they are not the last in the chain
        // (i.e. have other builds depending on them)
        verifyZeroInteractions(datadogClientMock);
    }

    private DatadogBuild pipelineBuild(long id) {
        return createBuild(id, true, 0);
    }

    private DatadogBuild compositeBuildWithDependents(long id) {
        return createBuild(id, true, 1);
    }

    private DatadogBuild createBuild(long id, boolean isComposite, int dependentOnMe) {
        return new DatadogBuild(id, "build", NORMAL, true, "Project", Date.from(now()), Date.from(now()), dependentOnMe);
    }
}
