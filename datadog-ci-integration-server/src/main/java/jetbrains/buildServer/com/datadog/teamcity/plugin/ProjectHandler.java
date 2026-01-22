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
        String apiKey = getBuildParameter(build, DATADOG_API_KEY_PARAM);
        String ddSite = getBuildParameter(build, DATADOG_SITE_PARAM);
        return new ProjectParameters(apiKey, ddSite);
    }

    public boolean isPluginEnabled(SBuild build) {
        String enabled = getBuildParameter(build, DATADOG_ENABLED_PARAM, null);
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

    private String getBuildParameter(SBuild build, String parameterName) {
        // Check parameter exists first
        String value = build.getParametersProvider().get(parameterName);
        if (value == null) {
            throw new IllegalArgumentException(
                    format("Could not find required property '%s' for build %s",
                            parameterName, build.getBuildId()));
        }

        return resolveBuildParameter(build, parameterName);
    }

    private String getBuildParameter(SBuild build, String parameterName, String defaultValue) {
        // Check if parameter exists
        String value = build.getParametersProvider().get(parameterName);
        if (value == null) {
            return defaultValue;
        }

        return resolveBuildParameter(build, parameterName);
    }

    private String resolveBuildParameter(SBuild build, String parameterName) {
        // Use build's ValueResolver to resolve any %references% and handle password parameters
        String paramReference = String.format("%%%s%%", parameterName);
        ValueResolver resolver = build.getValueResolver();
        ProcessingResult resolved = resolver.resolve(paramReference);
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
