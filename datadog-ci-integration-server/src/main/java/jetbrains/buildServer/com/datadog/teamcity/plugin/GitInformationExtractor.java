package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.serverSide.BuildRevision;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.VcsRootInstanceEx;
import jetbrains.buildServer.vcs.impl.VcsModificationEx;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Optional;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;

@Component
public class GitInformationExtractor {

    protected static final String USERNAME_STYLE_PROPERTY = "usernameStyle";
    protected static final String URL_PROPERTY = "url";
    protected static final String BRANCH_PROPERTY = "branch";
    protected static final String GIT_VCS = "jetbrains.git";
    protected static final String DEFAULT_EMAIL_DOMAIN = "TeamCity";

    private static final Logger LOG = Logger.getInstance(GitInformationExtractor.class.getName());

    private final ProjectHandler projectHandler;

    public GitInformationExtractor(ProjectHandler projectHandler) {
        this.projectHandler = projectHandler;
    }

    public Optional<GitInfo> extractGitInfo(SBuild build) {
        Optional<BuildRevision> revisionOptional = build.getRevisions().stream()
            .filter(this::hasGitRoot)
            .findFirst();
        if (!revisionOptional.isPresent()) {
            LOG.warn(format("Could not find revision for build '%s'. Revisions: %s", build, build.getRevisions()));
            return Optional.empty();
        }

        BuildRevision revision = revisionOptional.get();
        VcsRootInstanceEx vcsRootInstance = (VcsRootInstanceEx) revision.getRoot();
        VcsModificationEx gitModification = (VcsModificationEx) vcsRootInstance.findModificationByVersion(revision.getRevision());

        if (gitModification == null) {
            LOG.warn(format("Could not find modification for revision '%s' from VCS root '%s'", revision, vcsRootInstance));
            return Optional.empty();
        }

        UsernameStyle usernameStyle = getUsernameStyle(vcsRootInstance);
        GitUserInfo committerInfo = extractCommitterInfo(build, gitModification, usernameStyle);
        GitUserInfo authorInfo = tryExtractAuthorInfo(build, gitModification, usernameStyle)
            .orElse(committerInfo);

        return Optional.of(new GitInfo()
            .withRepositoryURL(vcsRootInstance.getProperty(URL_PROPERTY))
            .withDefaultBranch(vcsRootInstance.getProperty(BRANCH_PROPERTY))
            .withMessage(gitModification.getDescription().trim())
            .withSha(gitModification.getVersion())
            .withCommitTime(toRFC3339(gitModification.getCommitDate()))
            .withCommitterName(committerInfo.username)
            .withCommitterEmail(committerInfo.email)
            .withAuthorTime(toRFC3339(gitModification.getVcsDate()))
            .withAuthorName(authorInfo.username)
            .withAuthorEmail(authorInfo.email)
            .withBranch(getBranch(build)));
    }

    private boolean hasGitRoot(BuildRevision rev) {
        return rev.getRoot().getVcsName().equalsIgnoreCase(GIT_VCS);
    }

    private UsernameStyle getUsernameStyle(VcsRootInstanceEx vcsRootInstance) {
        String usernameStyle = vcsRootInstance.getProperty(USERNAME_STYLE_PROPERTY);
        if (usernameStyle == null || usernameStyle.isEmpty()) {
            throw new IllegalArgumentException("Could not retrieve username style from VCS root properties: " + vcsRootInstance.getProperties());
        }

        return UsernameStyle.valueOf(usernameStyle);
    }

    private GitUserInfo extractCommitterInfo(SBuild build, VcsModificationEx change, UsernameStyle usernameStyle) {
        String committerUsername = change.getCommiterName();
        return parseUsername(build, committerUsername, usernameStyle);
    }

    private Optional<GitUserInfo> tryExtractAuthorInfo(SBuild build, SVcsModification change, UsernameStyle usernameStyle) {
        String authorUsername = change.getUserName();
        if (authorUsername == null || authorUsername.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(parseUsername(build, authorUsername, usernameStyle));
    }

    @Nonnull
    private GitUserInfo parseUsername(SBuild build, String username, UsernameStyle usernameStyle) {
        switch (usernameStyle) {
            case FULL:
                return parseFullStyle(username);
            case EMAIL:
                return parseEmailStyle(username);
            case NAME:
            case USERID:
                // These styles do not have any email information, so we will generate one
                return parseStylesWithoutEmail(username, build);
            default:
                throw new IllegalArgumentException("Cannot recognize username style: " + usernameStyle);
        }
    }

    @Nonnull
    private GitUserInfo parseFullStyle(String username) {
        // Full style has name and email (example: 'John Doe <johndoe@gmail.com>')
        int emailStartIdx = username.indexOf("<");
        if (emailStartIdx == -1) {
            throw new IllegalArgumentException("Could not find email start for username " + username);
        }

        int emailEndIdx = username.lastIndexOf(">");
        if (emailEndIdx <= emailStartIdx) {
            throw new IllegalArgumentException("Could not find email end for username " + username);
        }

        String committerUsername = username.substring(0, emailStartIdx).trim();
        String committerEmail = username.substring(emailStartIdx + 1, emailEndIdx);
        return new GitUserInfo(committerUsername, committerEmail);
    }

    @Nonnull
    private static GitUserInfo parseEmailStyle(String email) {
        // Email style has only email (example: 'johndoe@gmail.com')
        int usernameEndIndex = email.indexOf("@");
        return usernameEndIndex == -1 ?
            new GitUserInfo(email, email) :
            new GitUserInfo(email.substring(0, usernameEndIndex), email);
    }

    @Nonnull
    private GitUserInfo parseStylesWithoutEmail(String username, SBuild build) {
        // In these cases we generate an email for the user, based on the email domain project parameter
        // or just using "TeamCity" if nothing is specified
        String emailDomain = projectHandler.getEmailDomainParameter(build).orElse(DEFAULT_EMAIL_DOMAIN);
        String emailUsername = username.replaceAll("\\s", "").toLowerCase();
        return new GitUserInfo(username, emailUsername + "@" + emailDomain);
    }

    private String getBranch(SBuild build) {
        return build.getBranch() == null ? "" : build.getBranch().getDisplayName();
    }

    private static class GitUserInfo {
        private final String username;
        private final String email;

        private GitUserInfo(String username, String email) {
            this.username = username;
            this.email = email;
        }
    }

    protected enum UsernameStyle {
        /**
         * Name (John Doe)
         */
        NAME,

        /**
         * User id based on email (johndoe)
         */
        USERID,

        /**
         * Email (johndoe@gmail.com)
         */
        EMAIL,

        /**
         * Name and Email (John Doe &lt;johndoe@gmail.com&gt;)
         */
        FULL
    }
}

