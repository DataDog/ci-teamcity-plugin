package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Optional;

import static java.lang.String.format;

@Component
public class ProjectHandler {

    protected static final String DATADOG_API_KEY_PARAM = "datadog.ci.api.key";
    protected static final String DATADOG_SITE_PARAM = "datadog.ci.site";

    // Clients are able to specify the email domain to be used if they are
    // using the username styles not containing the email (USERID and NAME)
    protected static final String EMAIL_DOMAIN_PARAM = "datadog.ci.email.domain";

    private final ProjectManager projectManager;

    public ProjectHandler(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    public ProjectParameters getProjectParameters(SBuild build) {
        ProjectEx project = getProject(build);
        String apiKey = project.getParameterValue(DATADOG_API_KEY_PARAM);
        String ddSite = project.getParameterValue(DATADOG_SITE_PARAM);

        if (apiKey == null || ddSite == null) {
            throw new IllegalArgumentException(
                    format("Could not find required properties '%s' and '%s' for project '%s'. Project parameters: %s",
                            DATADOG_API_KEY_PARAM, DATADOG_SITE_PARAM, project.getName(), project.getParameters()));
        }

        return new ProjectParameters(apiKey, ddSite);
    }

    @Nonnull
    private ProjectEx getProject(SBuild build) {
        return (ProjectEx) Optional.ofNullable(build.getProjectId())
            .map(projectManager::findProjectById)
            .orElse(projectManager.getRootProject());
    }

    public Optional<String> getEmailDomainParameter(SBuild build) {
        ProjectEx project = getProject(build);
        String emailDomain = project.getParameterValue(EMAIL_DOMAIN_PARAM);
        if (emailDomain == null || emailDomain.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(emailDomain);
    }

    public static class ProjectParameters {
        private final String apiKey;
        private final String ddSite;

        public ProjectParameters(String apiKey, String ddSite) {
            this.apiKey = apiKey;
            this.ddSite = ddSite;
        }

        public String apiKey() {
            return apiKey;
        }

        public String ddSite() {
            return ddSite;
        }
    }
}
