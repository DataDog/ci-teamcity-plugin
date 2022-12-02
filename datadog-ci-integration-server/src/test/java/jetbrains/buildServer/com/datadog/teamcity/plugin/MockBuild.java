package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.google.common.collect.ImmutableMap;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.Branch;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.serverSide.dependency.BuildDependency;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.VcsRootInstance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.CIEntityFactory.CHECKOUT_DIR;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//TODO I don't see any way to instantiate 'dummy' TC builds for testing purposes,
// so for now I'm creating this class able to construct mocked builds
public class MockBuild {

    public static final String DEFAULT_NAME = "Full Name";
    public static final String DEFAULT_PROJECT_ID = "Project ID";
    public static final String DEFAULT_REPO_URL = "repository-url.com";
    public static final String DEFAULT_BRANCH = "main";
    public static final String DEFAULT_COMMIT_SHA = "sha";
    public static final String DEFAULT_COMMIT_USERNAME = "username";
    public static final String DEFAULT_CHECKOUT_DIR = "default-checkout-dir";
    public static final String DEFAULT_NODE_HOSTNAME = "default-hostname";
    public static final String DEFAULT_NODE_NAME = "default-name";
    public static final String DEFAULT_FAILURE_MESSAGE = "default-failure-message";
    public static final Status DEFAULT_STATUS = Status.NORMAL;

    public static final Date DEFAULT_START_DATE = Date.from(Instant.ofEpochMilli(1000));
    public static final Date DEFAULT_END_DATE = Date.from(Instant.ofEpochMilli(1005));
    public static final Date DEFAULT_COMMIT_DATE = Date.from(Instant.ofEpochMilli(999));

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

        when(buildMock.getBranch()).thenReturn(b.branchMock);
        when(buildMock.getContainingChanges()).thenReturn(b.changesListMock);
        when(buildMock.getFailureReasons()).thenReturn(b.failureReasons);
        when(buildMock.isInternalError()).thenReturn(true);

        ParametersProvider parametersProviderMock = mock(ParametersProvider.class);
        when(parametersProviderMock.get(CHECKOUT_DIR)).thenReturn(DEFAULT_CHECKOUT_DIR);
        when(buildMock.getParametersProvider()).thenReturn(parametersProviderMock);

        BuildPromotion buildPromotionMock = mock(BuildPromotion.class);
        when(buildPromotionMock.getNumberOfDependedOnMe()).thenReturn(b.dependentsNum);
        when(buildPromotionMock.isCompositeBuild()).thenReturn(b.isComposite);
        when(buildPromotionMock.getAssociatedBuild()).thenReturn(buildMock);
        doReturn(b.dependents).when(buildPromotionMock).getDependedOnMe();
        when(buildMock.getBuildPromotion()).thenReturn(buildPromotionMock);

        when(buildMock.getTriggeredBy()).thenReturn(b.triggeredBy);
        when(buildMock.getAgent()).thenReturn(b.agentMock);

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

        List<SVcsModification> changesListMock = new ArrayList<>();
        private Branch branchMock;
        private SBuildAgent agentMock;
        private List<BuildProblemData> failureReasons = new ArrayList<>();

        public Builder(long id, MockBuild.BuildType buildType) {
            this.id = id;

            if (buildType == MockBuild.BuildType.PIPELINE) {
                this.isComposite = true;
            } else if (buildType == MockBuild.BuildType.JOB) {
                agentMock = mock(SBuildAgent.class);
                when(agentMock.getHostAddress()).thenReturn("");
                when(agentMock.getHostName()).thenReturn("");
            }
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

        public Builder withFailureReason(String type) {
            BuildProblemData failureMock = mock(BuildProblemData.class);
            when(failureMock.getDescription()).thenReturn(DEFAULT_FAILURE_MESSAGE);
            when(failureMock.getType()).thenReturn(type);
            this.failureReasons.add(failureMock);
            return this;
        }

        public Builder withPreviousAttempt(SFinishedBuild previousAttempt) {
            this.previousAttempt = previousAttempt;
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

        public Builder addGitInformation() {
            VcsRootInstance vcsRootInstanceMock = mock(VcsRootInstance.class);
            when(vcsRootInstanceMock.getProperties())
                    .thenReturn(ImmutableMap.of("url", DEFAULT_REPO_URL));
            when(vcsRootInstanceMock.getProperty("branch")).thenReturn(DEFAULT_BRANCH);

            branchMock = mock(Branch.class);
            when(branchMock.getDisplayName()).thenReturn(DEFAULT_BRANCH);

            ArrayList<SUser> committersMocks = new ArrayList<>();
            SUser userMock = mock(SUser.class);
            when(userMock.getUsername()).thenReturn(DEFAULT_COMMIT_USERNAME);
            committersMocks.add(userMock);

            SVcsModification changeMock = mock(SVcsModification.class);
            when(changeMock.getVcsRoot()).thenReturn(vcsRootInstanceMock);
            when(changeMock.getCommitters()).thenReturn(committersMocks);

            when(changeMock.getCommitDate()).thenReturn(DEFAULT_COMMIT_DATE);
            when(changeMock.getVcsDate()).thenReturn(DEFAULT_COMMIT_DATE);
            when(changeMock.getDescription()).thenReturn("Description");
            when(changeMock.getVersion()).thenReturn(DEFAULT_COMMIT_SHA);
            when(changeMock.getUserName()).thenReturn(DEFAULT_COMMIT_USERNAME);

            changesListMock.add(changeMock);
            return this;
        }

        public Builder addNodeInformation() {
            when(agentMock.getHostAddress()).thenReturn(DEFAULT_NODE_HOSTNAME);
            when(agentMock.getHostName()).thenReturn(DEFAULT_NODE_NAME);
            return this;
        }

        public SBuild build() {
            return fromBuilder(this);
        }

        public SFinishedBuild buildFinished() {
            return fromBuilder(this, SFinishedBuild.class);
        }
    }

    enum BuildType {
        JOB, PIPELINE;
    }
}
