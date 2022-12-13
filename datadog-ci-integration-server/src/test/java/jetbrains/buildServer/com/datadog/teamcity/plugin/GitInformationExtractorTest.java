package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.DEFAULT_EMAIL_DOMAIN;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.GIT_VCS;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.EMAIL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.FULL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.USERID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_GIT_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REPO_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GitInformationExtractorTest {

    @Mock
    private ProjectHandler projectHandlerMock;

    private GitInformationExtractor gitInfoExtractor;

    @Before
    public void setUp() {
        when(projectHandlerMock.getEmailDomainParameter(any())).thenReturn(Optional.empty());
        gitInfoExtractor = new GitInformationExtractor(projectHandlerMock);
    }

    @Test
    public void shouldReturnEmptyIfNoRevisionIsFound() {
        SBuild build = new MockBuild.Builder(1, PIPELINE).build();

        Optional<GitInfo> gitInfo = gitInfoExtractor.extractGitInfo(build);

        assertThat(gitInfo).isEmpty();
    }

    @Test
    public void shouldReturnEmptyForNonGitRevisions() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision("NOT-GIT", "", "", emptyList())
            .build();

        Optional<GitInfo> gitInfo = gitInfoExtractor.extractGitInfo(build);

        assertThat(gitInfo).isEmpty();
    }

    @Test
    public void shouldParseCorrectFullStyleUsername() {
        // Setup
        String fullUsername = "John Doe <johndoe@datadog.com>";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), fullUsername, emptyList())
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("John Doe")
            .withCommitterName("John Doe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForInvalidFullStyleUsername() {
        // Setup: username is missing the "<email>" part
        String invalidFullUsername = "Invalid Full Username";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), invalidFullUsername, emptyList())
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test
    public void shouldParseCorrectEmailUsername() {
        // Setup
        String emailUsername = "johndoe@datadog.com";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, EMAIL.name(), emailUsername, emptyList())
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("johndoe")
            .withCommitterName("johndoe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldCreateDefaultEmailForNameStyle() {
        // Setup
        String emailUsername = "John Doe";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, NAME.name(), emailUsername, emptyList())
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("John Doe")
            .withCommitterName("John Doe")
            .withAuthorEmail("johndoe@" + DEFAULT_EMAIL_DOMAIN)
            .withCommitterEmail("johndoe@" + DEFAULT_EMAIL_DOMAIN);

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldCreateDefaultEmailForUserIDStyle() {
        // Setup
        String userIdUsername = "johndoe";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, USERID.name(), userIdUsername, emptyList())
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("johndoe")
            .withCommitterName("johndoe")
            .withAuthorEmail("johndoe@" + DEFAULT_EMAIL_DOMAIN)
            .withCommitterEmail("johndoe@" + DEFAULT_EMAIL_DOMAIN);

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseProvidedEmailDomainForUserIDStyle() {
        // Setup
        String userIdUsername = "johndoe";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, USERID.name(), userIdUsername, emptyList())
            .build();

        when(projectHandlerMock.getEmailDomainParameter(build))
            .thenReturn(Optional.of("datadog.com"));

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then: the provided email domain should be used
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("johndoe")
            .withCommitterName("johndoe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseCommitterUsernameIfChangeUsernameNotPresent() {
        // Setup
        String fullUsername = "John Doe <johndoe@datadog.com>";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), null, singletonList(fullUsername))
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("John Doe")
            .withCommitterName("John Doe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameStyleIsEmpty() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, "", DEFAULT_COMMIT_USERNAME, emptyList())
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameStyleIsNull() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, null, DEFAULT_COMMIT_USERNAME, emptyList())
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameAndCommittersAreMissing() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), "", emptyList())
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameMissingAndCommitterNameIsEmpty() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), "", singletonList(""))
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForInvalidUsernameStyle() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, "INVALID", "John Doe <johndoe@datadog.com>", emptyList())
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    private static GitInfo defaultGitInfo() {
        return new GitInfo()
            .withSha(DEFAULT_COMMIT_SHA)
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withBranch(DEFAULT_BRANCH)
            .withDefaultBranch(DEFAULT_BRANCH)
            .withRepositoryURL(DEFAULT_REPO_URL)
            .withMessage(DEFAULT_GIT_MESSAGE);
    }
}
