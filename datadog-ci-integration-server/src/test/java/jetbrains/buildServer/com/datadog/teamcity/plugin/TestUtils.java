package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;

import java.sql.Date;

import static java.time.Instant.now;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestUtils {

    //TODO I don't see any way to instantiate 'dummy' TC builds for testing purposes,
    // so for now I'm mocking the actual object. I'll check with TC on this
    public static SBuild mockSBuild(long id, boolean isComposite, int dependentOnMe) {
        SBuild compositeBuildMock = mock(SBuild.class);
        when(compositeBuildMock.getBuildId()).thenReturn(id);
        when(compositeBuildMock.getFullName()).thenReturn("Full name");
        when(compositeBuildMock.getBuildStatus()).thenReturn(Status.NORMAL);
        when(compositeBuildMock.getProjectId()).thenReturn("Project ID");
        when(compositeBuildMock.isCompositeBuild()).thenReturn(isComposite);
        when(compositeBuildMock.getStartDate()).thenReturn(Date.from(now()));
        when(compositeBuildMock.getFinishDate()).thenReturn(Date.from(now()));

        BuildPromotion buildPromotionMock = mock(BuildPromotion.class);
        when(buildPromotionMock.getNumberOfDependedOnMe()).thenReturn(dependentOnMe);
        when(buildPromotionMock.isCompositeBuild()).thenReturn(isComposite);
        when(compositeBuildMock.getBuildPromotion()).thenReturn(buildPromotionMock);

        return compositeBuildMock;
    }

    private TestUtils() { }
}
