package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Optional;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PROJECT_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public abstract class BaseNotificatorProcessingTest {

    @Mock protected DatadogClient datadogClientMock;
    @Mock protected ProjectHandler projectHandlerMock;
    @Mock protected BuildsManager buildsManagerMock;
    @Mock protected NotificatorRegistry notificatorRegistryMock;
    @Mock protected SBuildServer buildServerMock;
    @Mock protected BuildDependenciesManager dependenciesManagerMock;

    protected DatadogNotificator datadogNotificator;

    @Before
    public void setUp() {
        when(buildServerMock.getRootUrl()).thenReturn("root-url");
        when(projectHandlerMock.getProjectParameters(Optional.of(DEFAULT_PROJECT_ID)))
                .thenReturn(new ProjectHandler.ProjectParameters(TEST_API_KEY, TEST_DD_SITE));

        CIEntityFactory entityCreator = new CIEntityFactory(buildServerMock, dependenciesManagerMock);
        datadogNotificator = new DatadogNotificator(
                notificatorRegistryMock, buildsManagerMock, datadogClientMock, projectHandlerMock, entityCreator);
    }
}
