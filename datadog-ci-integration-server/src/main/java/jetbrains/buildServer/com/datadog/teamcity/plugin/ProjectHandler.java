package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.lang.String.format;

@Component
public class ProjectHandler {

    protected static final String DATADOG_API_KEY_PARAM = "datadog.ci.api.key";
    protected static final String DATADOG_SITE_PARAM = "datadog.ci.site";

    private final ProjectManager projectManager;

    public ProjectHandler(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    public ProjectParameters getProjectParameters(SBuild build) {
        ProjectEx project = (ProjectEx) Optional.ofNullable(build.getProjectId())
            .map(projectManager::findProjectById)
            .orElse(projectManager.getRootProject());

        String apiKey = project.getParameterValue(DATADOG_API_KEY_PARAM);
        String ddSite = project.getParameterValue(DATADOG_SITE_PARAM);
        if (apiKey == null || ddSite == null) {
            throw new IllegalArgumentException(
                    format("Could not find required properties '%s' and '%s' for project '%s'. Project parameters: %s",
                            DATADOG_API_KEY_PARAM, DATADOG_SITE_PARAM, project.getName(), project.getParameters()));
        }

        return new ProjectParameters(apiKey, ddSite);
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
