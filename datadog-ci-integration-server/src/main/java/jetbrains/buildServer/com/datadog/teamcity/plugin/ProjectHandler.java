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
    protected static final String DATADOG_BATCH_SIZE_PARAM = "datadog.ci.batch.size";
    
    private static final int DEFAULT_BATCH_SIZE = 20;

    private final ProjectManager projectManager;

    public ProjectHandler(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    public ProjectParameters getProjectParameters(SBuild build) {
        ProjectEx project = getProject(build);
        String apiKey = getApiKey(project);
        String ddSite = project.getParameterValue(DATADOG_SITE_PARAM);

        if (ddSite == null) {
            throw new IllegalArgumentException(
                    format("Could not find required property '%s' for project '%s'. Project parameters: %s",
                            DATADOG_SITE_PARAM, project.getName(), project.getParameters()));
        }
        
        int batchSize = getBatchSize(project);

        return new ProjectParameters(apiKey, ddSite, batchSize);
    }

    public boolean isPluginEnabled(SBuild build) {
        ProjectEx project = getProject(build);
        String enabled = project.getParameterValue(DATADOG_ENABLED_PARAM);
        boolean isPluginEnabled = Boolean.parseBoolean(enabled);
        if (!isPluginEnabled) {
            LOG.debug(format("Plugin not enabled in project '%s'", project.getFullName()));
        }

        return isPluginEnabled;
    }

    @Nonnull
    private ProjectEx getProject(SBuild build) {
        return (ProjectEx) Optional.ofNullable(build.getProjectId())
            .map(projectManager::findProjectById)
            .orElse(projectManager.getRootProject());
    }

    private String getApiKey(ProjectEx project) {
        String apiKeyReference = String.format("%%%s%%", DATADOG_API_KEY_PARAM);
        ValueResolver resolver = project.getValueResolver();
        ProcessingResult resolved = resolver.resolve(apiKeyReference);
        if (!resolved.isFullyResolved()) {
            throw new IllegalArgumentException(
                    format("Could not find required property '%s' for project '%s'. Project parameters: %s",
                            DATADOG_API_KEY_PARAM, project.getName(), project.getParameters()));
        }

        return resolved.getResult();
    }
    
    private int getBatchSize(ProjectEx project) {
        String batchSizeStr = project.getParameterValue(DATADOG_BATCH_SIZE_PARAM);
        if (batchSizeStr == null || batchSizeStr.trim().isEmpty()) {
            return DEFAULT_BATCH_SIZE;
        }
        
        try {
            int batchSize = Integer.parseInt(batchSizeStr.trim());
            if (batchSize > 0) {
                return batchSize;
            }
        } catch (NumberFormatException e) {}
        
        LOG.warn(format("Invalid batch size value '%s' for project '%s'. Using default: %d", 
            batchSizeStr, project.getName(), DEFAULT_BATCH_SIZE));
        return DEFAULT_BATCH_SIZE;
    }

    public static class ProjectParameters {
        private final String apiKey;
        private final String ddSite;
        private final int batchSize;

        public ProjectParameters(String apiKey, String ddSite, int batchSize) {
            this.apiKey = apiKey;
            this.ddSite = ddSite;
            this.batchSize = batchSize;
        }

        public String apiKey() {
            return apiKey;
        }

        public String ddSite() {
            return ddSite;
        }
        
        public int batchSize() {
            return batchSize;
        }
    }
}
