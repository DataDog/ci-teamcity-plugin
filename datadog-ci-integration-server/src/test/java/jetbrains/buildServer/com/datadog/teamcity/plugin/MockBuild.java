package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.Branch;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionOwner;
import jetbrains.buildServer.serverSide.BuildRevision;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.serverSide.dependency.BuildDependency;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.VcsRootInstanceEx;
import jetbrains.buildServer.vcs.impl.VcsModificationEx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildChainProcessor.CHECKOUT_DIR_PROPERTY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.BRANCH_PROPERTY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.URL_PROPERTY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.USERNAME_STYLE_PROPERTY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_CHECKOUT_DIR;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_FAILURE_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_GIT_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_HOSTNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PROJECT_ID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_QUEUE_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REPO_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REVISION;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_STATUS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Currently there is no way to create TeamCity builds for testing purposes. Therefore, this class can be used
// to create a mock build, returning default parameters unless overridden during creation.
public class MockBuild {

    public static SBuild fromBuilder(Builder b) {
        // Build information mocks
        SBuild buildMock = mock(SBuild.class);
        when(buildMock.getBuildId()).thenReturn(b.id);
        when(buildMock.getFullName()).thenReturn(b.fullName);
        when(buildMock.getBuildStatus()).thenReturn(b.status);
        when(buildMock.getProjectId()).thenReturn(b.projectID);
        when(buildMock.isCompositeBuild()).thenReturn(b.isComposite);
        when(buildMock.getStartDate()).thenReturn(b.startDate);
        when(buildMock.getFinishDate()).thenReturn(b.endDate);
        when(buildMock.getQueuedDate()).thenReturn(b.queueDate);
        when(buildMock.isPersonal()).thenReturn(b.isPersonal);
        when(buildMock.getBranch()).thenReturn(b.branchMock);
        when(buildMock.getTags()).thenReturn(b.tags);
        when(buildMock.getContainingChanges()).thenReturn(b.changesListMock);
        when(buildMock.getFailureReasons()).thenReturn(b.failureReasons);
        when(buildMock.getRevisions()).thenReturn(b.revisions);
        when(buildMock.isInternalError()).thenReturn(true);
        when(buildMock.getTriggeredBy()).thenReturn(b.triggeredBy);
        when(buildMock.getAgent()).thenReturn(b.agentMock);

        // Build promotion mocks
        BuildPromotion buildPromotionMock = mock(BuildPromotion.class);
        when(buildPromotionMock.getNumberOfDependedOnMe()).thenReturn(b.dependentsNum);
        when(buildPromotionMock.isCompositeBuild()).thenReturn(b.isComposite);
        when(buildPromotionMock.getAssociatedBuild()).thenReturn(buildMock);
        when(buildPromotionMock.getAssociatedBuildId()).thenReturn(b.id);
        doReturn(b.dependencies).when(buildPromotionMock).getDependencies();
        doReturn(b.allDependencies).when(buildPromotionMock).getAllDependencies();
        when(buildMock.getBuildPromotion()).thenReturn(buildPromotionMock);

        // Build parameters mocks
        ParametersProvider parametersProviderMock = mock(ParametersProvider.class);
        when(parametersProviderMock.get(CHECKOUT_DIR_PROPERTY)).thenReturn(DEFAULT_CHECKOUT_DIR);
        when(buildMock.getParametersProvider()).thenReturn(parametersProviderMock);

        return buildMock;
    }

    public static class Builder {
        // Build customizable info
        private final long id;
        private boolean isComposite;
        private boolean isPersonal;
        private String fullName = DEFAULT_NAME;
        private Status status = DEFAULT_STATUS;
        private String projectID = DEFAULT_PROJECT_ID;
        private Date startDate = DEFAULT_START_DATE;
        private Date endDate = DEFAULT_END_DATE;
        private Date queueDate = DEFAULT_QUEUE_DATE;
        private Branch branchMock;
        private List<String> tags = new ArrayList<>();
        private final List<SVcsModification> changesListMock = new ArrayList<>();
        private final List<BuildProblemData> failureReasons = new ArrayList<>();
        private final List<BuildRevision> revisions = new ArrayList<>();
        private final TriggeredBy triggeredBy = mock(TriggeredBy.class);
        private SBuildAgent agentMock;

        // Build Promotion customizable info
        private int dependentsNum;
        private List<BuildDependency> dependencies = new ArrayList<>();
        private List<BuildPromotion> allDependencies = new ArrayList<>();

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

        public Builder isPersonal() {
            this.isPersonal = true;
            return this;
        }

        public Builder isComposite() {
            this.isComposite = true;
            return this;
        }

        public Builder isTriggeredByRetry() {
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

        public Builder withQueueDate(Date queueDate) {
            this.queueDate = queueDate;
            return this;
        }

        public Builder withFailureReason(String type) {
            BuildProblemData failureMock = mock(BuildProblemData.class);
            when(failureMock.getDescription()).thenReturn(DEFAULT_FAILURE_MESSAGE);
            when(failureMock.getType()).thenReturn(type);
            this.failureReasons.add(failureMock);
            this.status = Status.FAILURE;
            return this;
        }

        public Builder withNumOfDependents(int dependentsNum) {
            this.dependentsNum = dependentsNum;
            return this;
        }

        public Builder withAllDependencies(List<SBuild> dependencies) {
            this.allDependencies = dependencies.stream()
                    .map(BuildPromotionOwner::getBuildPromotion)
                    .collect(toList());
            return this;
        }

        public Builder withDependencies(List<SBuild> dependencies) {
            this.dependencies = dependencies.stream().map(build -> {
                    BuildDependency dependencyMock = mock(BuildDependency.class);
                    BuildPromotion buildPromotion = build.getBuildPromotion();
                    when(dependencyMock.getDependOn()).thenReturn(buildPromotion);
                    return dependencyMock;
                })
                .collect(toList());
            return this;
        }

        public Builder withTags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder addRevision(String vcsName,
                                   String usernameStyle,
                                   String committerUsername,
                                   String authorUsername) {
            VcsModificationEx changeMock = mock(VcsModificationEx.class);
            when(changeMock.getDescription()).thenReturn(DEFAULT_GIT_MESSAGE);
            when(changeMock.getVersion()).thenReturn(DEFAULT_COMMIT_SHA);
            when(changeMock.getCommitDate()).thenReturn(DEFAULT_COMMIT_DATE);
            when(changeMock.getVcsDate()).thenReturn(DEFAULT_COMMIT_DATE);
            when(changeMock.getCommiterName()).thenReturn(committerUsername);
            when(changeMock.getUserName()).thenReturn(authorUsername);

            VcsRootInstanceEx vcsRootMock = mock(VcsRootInstanceEx.class);
            when(vcsRootMock.getVcsName()).thenReturn(vcsName);
            when(vcsRootMock.findModificationByVersion(DEFAULT_REVISION)).thenReturn(changeMock);
            when(vcsRootMock.getProperty(URL_PROPERTY)).thenReturn(DEFAULT_REPO_URL);
            when(vcsRootMock.getProperty(BRANCH_PROPERTY)).thenReturn(DEFAULT_BRANCH);
            when(vcsRootMock.getProperty(USERNAME_STYLE_PROPERTY)).thenReturn(usernameStyle);

            BuildRevision revisionMock = mock(BuildRevision.class);
            when(revisionMock.getRoot()).thenReturn(vcsRootMock);
            when(revisionMock.getRevision()).thenReturn(DEFAULT_REVISION);

            branchMock = mock(Branch.class);
            when(branchMock.getDisplayName()).thenReturn(DEFAULT_BRANCH);

            revisions.add(revisionMock);
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
    }

    enum BuildType {
        JOB, PIPELINE;
    }
}
