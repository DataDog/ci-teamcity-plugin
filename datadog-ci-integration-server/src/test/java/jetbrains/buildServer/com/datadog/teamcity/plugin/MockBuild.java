package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.serverSide.dependency.BuildDependency;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.mockito.Mockito.*;

//TODO I don't see any way to instantiate 'dummy' TC builds for testing purposes,
// so for now I'm creating this class able to construct mocked builds
public class MockBuild {

    public static final String DEFAULT_NAME = "Full Name";
    public static final String DEFAULT_PROJECT_ID = "Project ID";
    public static final Status DEFAULT_STATUS = Status.NORMAL;

    public static final Date DEFAULT_START_DATE = Date.from(Instant.ofEpochMilli(1000));
    public static final Date DEFAULT_END_DATE = Date.from(Instant.ofEpochMilli(1005));

    public static <T extends SBuild> T fromBuilder(Builder b, Class<T> clazz) {
        T buildMock = mock(clazz);
        when(buildMock.getBuildId()).thenReturn(b.id);
        when(buildMock.getFullName()).thenReturn(b.fullName);
        when(buildMock.getBuildStatus()).thenReturn(b.status);
        when(buildMock.getProjectId()).thenReturn(b.projectID);
        when(buildMock.isCompositeBuild()).thenReturn(b.isComposite);
        when(buildMock.getStartDate()).thenReturn(b.startDate);
        when(buildMock.getFinishDate()).thenReturn(b.endDate);
        when(buildMock.getPreviousFinished()).thenReturn(b.previousAttempt);

        BuildPromotion buildPromotionMock = mock(BuildPromotion.class);
        when(buildPromotionMock.getNumberOfDependedOnMe()).thenReturn(b.dependentsNum);
        when(buildPromotionMock.isCompositeBuild()).thenReturn(b.isComposite);
        when(buildPromotionMock.getAssociatedBuild()).thenReturn(buildMock);
        doReturn(b.dependents).when(buildPromotionMock).getDependedOnMe();
        when(buildMock.getBuildPromotion()).thenReturn(buildPromotionMock);

        when(buildMock.getTriggeredBy()).thenReturn(b.triggeredBy);

        return buildMock;
    }

    public static SBuild fromBuilder(Builder b) {
        return fromBuilder(b, SBuild.class);
    }

    public static class Builder {
        private final long id;
        private final TriggeredBy triggeredBy = mock(TriggeredBy.class);

        private boolean isComposite;

        private int dependentsNum;
        private String fullName = DEFAULT_NAME;
        private Status status = DEFAULT_STATUS;
        private String projectID = DEFAULT_PROJECT_ID;
        private Date startDate = DEFAULT_START_DATE;
        private Date endDate = DEFAULT_END_DATE;

        private SFinishedBuild previousAttempt;
        private List<BuildDependency> dependents;

        public Builder(long id) {
            this.id = id;
        }

        public Builder isTriggeredByUser() {
            when(triggeredBy.isTriggeredByUser()).thenReturn(true);
            return this;
        }

        public Builder isTriggeredByReply() {
            when(triggeredBy.getParameters()).thenReturn(Collections.singletonMap("type", "retry"));
            return this;
        }

        public Builder withFullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder withStatus(Status status) {
            this.status = status;
            return this;
        }

        public Builder withProjectID(String projectID) {
            this.projectID = projectID;
            return this;
        }

        public Builder withStartDate(Date startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder withEndDate(Date endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder withPreviousAttempt(SFinishedBuild previousAttempt) {
            this.previousAttempt = previousAttempt;
            return this;
        }

        public Builder isComposite() {
            this.isComposite = true;
            return this;
        }

        public Builder withNumOfDependents(int dependentsNum) {
            this.dependentsNum = dependentsNum;
            return this;
        }

        public Builder withDependents(List<SBuild> dependents) {
            this.dependents = dependents.stream().map(build -> {
                        BuildDependency dependencyMock = mock(BuildDependency.class);
                        BuildPromotion buildPromotion = build.getBuildPromotion();
                        when(dependencyMock.getDependent()).thenReturn(buildPromotion);
                        return dependencyMock;
                    })
                    .collect(toList());
            return this;
        }

        public SBuild build() {
            return fromBuilder(this);
        }

        public SFinishedBuild buildFinished() {
            return fromBuilder(this, SFinishedBuild.class);
        }
    }
}
