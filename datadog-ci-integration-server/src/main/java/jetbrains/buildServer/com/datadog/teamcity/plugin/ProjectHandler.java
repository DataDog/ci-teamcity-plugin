/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.parameters.ProcessingResult;
import jetbrains.buildServer.parameters.ValueResolver;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Optional;

import static java.lang.String.format;

@Component
public class ProjectHandler {

    private static final Logger LOG = Logger.getInstance(ProjectHandler.class.getName());

    protected static final String DATADOG_API_KEY_PARAM = "datadog.ci.api.key";
    protected static final String DATADOG_SITE_PARAM = "datadog.ci.site";
    protected static final String DATADOG_ENABLED_PARAM = "datadog.ci.enabled";

    private final ProjectManager projectManager;

    public ProjectHandler(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    public ProjectParameters getProjectParameters(SBuild build) {
        String apiKey = getApiKey(build);
        
        // Site is a regular parameter, use ParametersProvider which includes build + project hierarchy
        String ddSite = build.getParametersProvider().get(DATADOG_SITE_PARAM);
        if (ddSite == null) {
            throw new IllegalArgumentException(
                    format("Could not find required property '%s' for build %s",
                            DATADOG_SITE_PARAM, build.getBuildId()));
        }

        return new ProjectParameters(apiKey, ddSite);
    }

    public boolean isPluginEnabled(SBuild build) {
        String enabled = build.getParametersProvider().get(DATADOG_ENABLED_PARAM);
        boolean isPluginEnabled = Boolean.parseBoolean(enabled);
        if (!isPluginEnabled) {
            LOG.debug(format("Plugin not enabled for build %s", build.getBuildId()));
        }

        return isPluginEnabled;
    }

    @Nonnull
    private ProjectEx getProject(SBuild build) {
        return (ProjectEx) Optional.ofNullable(build.getProjectId())
            .map(projectManager::findProjectById)
            .orElse(projectManager.getRootProject());
    }

    private String getApiKey(SBuild build) {
        // API key is a password parameter and needs ValueResolver to get the unscrambled value
        // ValueResolver resolves from the project hierarchy, which includes job-level overrides
        ProjectEx project = getProject(build);
        String apiKeyReference = String.format("%%%s%%", DATADOG_API_KEY_PARAM);
        ValueResolver resolver = project.getValueResolver();
        ProcessingResult resolved = resolver.resolve(apiKeyReference);
        if (!resolved.isFullyResolved()) {
            throw new IllegalArgumentException(
                    format("Could not find required property '%s' for build %s",
                            DATADOG_API_KEY_PARAM, build.getBuildId()));
        }
        
        return resolved.getResult();
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
