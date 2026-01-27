/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.HostInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook.PipelineStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.ServerSettings;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildName;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.isPartialRetry;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.pipelineStartWithOffset;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.queueTimeMs;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo.ErrorDomain.PROVIDER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo.ErrorDomain.USER;
import static jetbrains.buildServer.messages.ErrorData.SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE;

@Component
public class BuildChainProcessor {

    // We only support these failure types as they usually have more descriptive messages
    // (Otherwise we might encounter things like "exit status code 1")
    private static final Map<String, String> SUPPORTED_FAILURE_TYPES_MAP = new HashMap<String, String>() {{
        put(TC_FAILED_TESTS_TYPE, "Tests Failed");
        put(SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE, "Snapshot Dependencies Failed");
    }};


    protected static final String CHECKOUT_DIR_PROPERTY = "system.teamcity.build.checkoutDir";
    protected static final String DEFAULT_SCHEME = "http";

    private static final Logger LOG = Logger.getInstance(BuildChainProcessor.class.getName());

    private final SBuildServer buildServer;
    private final DatadogClient datadogClient;
    private final ProjectHandler projectHandler;
    private final GitInformationExtractor gitInformationExtractor;
    private final ServerSettings serverSettings;

    public BuildChainProcessor(SBuildServer buildServer, DatadogClient datadogClient, ProjectHandler projectHandler, GitInformationExtractor gitInformationExtractor, ServerSettings serverSettings) {
        this.buildServer = buildServer;
        this.datadogClient = datadogClient;
        this.projectHandler = projectHandler;
        this.gitInformationExtractor = gitInformationExtractor;
        this.serverSettings = serverSettings;
    }

    public void process(SBuild pipelineBuild) {
        LOG.info("=== Processing Pipeline Build ===");
        logTriggeredByInfo(pipelineBuild, "PIPELINE");
        
        ProjectParameters params = projectHandler.getProjectParameters(pipelineBuild);
        List<Webhook> webhooks = createWebhooks(pipelineBuild);

        datadogClient.sendWebhooksAsync(webhooks, params.apiKey(), params.ddSite());
    }

    /**
     * Creates all the webhooks for a build chain. There will be 1 pipeline webhook for the final
     * composite build and <em>N</em> webhooks for the eligible job builds in the chain.
     */
    List<Webhook> createWebhooks(SBuild pipelineBuild) {
        // First, gather all builds in the chain to get accurate timing information
        LOG.info("=== Processing Dependencies ===");
        ChainMembership chainInfo = gatherChainMembers(pipelineBuild);
        
        LOG.info(format("Found %d builds in trigger chain", chainInfo.acceptedBuilds.size()));
        LOG.info(format("Chain queue time: %s", chainInfo.minQueueTime));
        LOG.info(format("Chain end time: %s", chainInfo.maxFinishTime));
        
        // Create pipeline webhook with expanded timing from entire chain
        PipelineWebhook pipelineWebhook = createPipelineWebhook(pipelineBuild, chainInfo);
        List<Webhook> webhooks = new ArrayList<>(singletonList(pipelineWebhook));
        
        // Create job webhooks for all accepted chain members
        String pipelineName = buildName(pipelineBuild);
        String pipelineID = buildID(pipelineBuild);
        List<JobWebhook> jobWebhooks = chainInfo.acceptedBuilds.stream()
            .filter(build -> !shouldBeIgnored(build))
            .map(job -> createJobWebhook(job, pipelineName, pipelineID))
            .collect(toList());
        webhooks.addAll(jobWebhooks);

        // Adding git information to all webhooks
        Optional<GitInfo> gitInfoOptional = gitInformationExtractor.extractGitInfo(pipelineBuild);
        gitInfoOptional.ifPresent(gitInfo -> webhooks.forEach(webhook -> webhook.setGitInfo(gitInfo)));

        return webhooks;
    }

    private PipelineWebhook createPipelineWebhook(SBuild pipelineBuild, ChainMembership chainInfo) {
        // Use chain timing (from earliest queue time to latest finish time)
        PipelineWebhook pipelineWebhook = new PipelineWebhook(
            buildName(pipelineBuild),
            buildURL(pipelineBuild),
            toRFC3339(chainInfo.minQueueTime),
            toRFC3339(chainInfo.maxFinishTime),
            buildID(pipelineBuild),
            String.valueOf(pipelineBuild.getBuildId()),
            isPartialRetry(pipelineBuild),
            getPipelineStatus(pipelineBuild));

        if (!pipelineBuild.getTags().isEmpty()) {
            pipelineWebhook.setTags(pipelineBuild.getTags());
        }

        return pipelineWebhook;
    }

    private PipelineStatus getPipelineStatus(SBuild pipelineBuild) {
        Status buildStatus = pipelineBuild.getBuildStatus();
        if (buildStatus.isSuccessful()) {
            return PipelineStatus.SUCCESS;
        } else if (buildStatus.isFailed()) {
            return PipelineStatus.ERROR;
        } else if (pipelineBuild.getCanceledInfo() != null) {
            return PipelineStatus.CANCELED;
        }

        throw new IllegalArgumentException("Pipeline status not recognized: " + buildStatus);
    }

    /**
     * Recursively gathers all builds that are part of the same trigger chain.
     * Handles diamond-shaped dependencies where a build might be visited before its triggering parent is accepted.
     * Also tracks min/max times across all accepted builds.
     */
    private ChainMembership gatherChainMembers(SBuild pipelineBuild) {
        // Start with the pipeline build as accepted
        Map<Long, SBuild> acceptedBuilds = new HashMap<>();
        acceptedBuilds.put(pipelineBuild.getBuildId(), pipelineBuild);
        
        Date minQueueTime = pipelineBuild.getQueuedDate();
        Date maxFinishTime = pipelineBuild.getFinishDate();
        
        // Get all dependencies (flattened graph)
        List<SBuild> allDependencies = pipelineBuild.getBuildPromotion().getAllDependencies().stream()
            .map(BuildPromotion::getAssociatedBuild)
            .filter(Objects::nonNull)
            .collect(toList());
        
        LOG.info(format("Total dependencies to examine: %d", allDependencies.size()));
        
        // Iteratively accept builds until no more can be accepted
        // This handles diamond dependencies where we might need multiple passes
        boolean changed = true;
        int iteration = 0;
        while (changed) {
            iteration++;
            changed = false;
            LOG.debug(format("Chain membership iteration %d", iteration));
            
            for (SBuild dependency : allDependencies) {
                // Skip if already accepted
                if (acceptedBuilds.containsKey(dependency.getBuildId())) {
                    continue;
                }
                
                // Log trigger info for visibility
                logTriggeredByInfo(dependency, "DEPENDENCY");
                
                // Check if this dependency was triggered by an accepted build
                if (isTriggeredByAcceptedBuild(dependency, acceptedBuilds.keySet())) {
                    LOG.info(format("Accepting build #%d '%s' into chain", dependency.getBuildId(), buildName(dependency)));
                    acceptedBuilds.put(dependency.getBuildId(), dependency);
                    
                    // Update min/max times (use queue time to include time spent waiting)
                    if (dependency.getQueuedDate().before(minQueueTime)) {
                        minQueueTime = dependency.getQueuedDate();
                    }
                    if (dependency.getFinishDate() != null && dependency.getFinishDate().after(maxFinishTime)) {
                        maxFinishTime = dependency.getFinishDate();
                    }
                    
                    changed = true;
                } else {
                    LOG.debug(format("Build #%d '%s' not triggered by accepted build", dependency.getBuildId(), buildName(dependency)));
                }
            }
        }
        
        LOG.info(format("Chain membership resolved after %d iterations", iteration));
        
        // Return all accepted builds (for composite head builds, exclude the head; for non-composite, include it)
        List<SBuild> chainMembers;
        if (pipelineBuild.isCompositeBuild()) {
            // Composite builds: exclude the head build (it's just the pipeline frame)
            chainMembers = acceptedBuilds.values().stream()
                .filter(build -> build.getBuildId() != pipelineBuild.getBuildId())
                .collect(toList());
        } else {
            // Non-composite builds: include the head build as a job
            chainMembers = new ArrayList<>(acceptedBuilds.values());
        }
            
        return new ChainMembership(chainMembers, minQueueTime, maxFinishTime);
    }
    
    /**
     * Checks if a build was triggered by one of the accepted builds in the chain.
     */
    private boolean isTriggeredByAcceptedBuild(SBuild build, Set<Long> acceptedBuildIds) {
        try {
            jetbrains.buildServer.serverSide.TriggeredBy triggeredBy = build.getTriggeredBy();
            if (triggeredBy == null) {
                return false;
            }
            
            Map<String, String> params = triggeredBy.getParameters();
            
            // Check if triggered as snapshot dependency
            if (!"snapshotDependency".equals(params.get("type"))) {
                return false;
            }
            
            // Check if triggered by a build in the accepted set
            String triggeringBuildId = params.get("buildId");
            if (triggeringBuildId == null) {
                return false;
            }
            
            try {
                long buildId = Long.parseLong(triggeringBuildId);
                return acceptedBuildIds.contains(buildId);
            } catch (NumberFormatException e) {
                LOG.warn(format("Invalid buildId in trigger parameters: %s", triggeringBuildId));
                return false;
            }
        } catch (Exception e) {
            LOG.warn(format("Failed to check trigger for build %s: %s", build.getBuildId(), e.getMessage()), e);
            return false;
        }
    }
    
    /**
     * Container for chain membership information
     */
    private static class ChainMembership {
        final List<SBuild> acceptedBuilds;
        final Date minQueueTime;
        final Date maxFinishTime;
        
        ChainMembership(List<SBuild> acceptedBuilds, Date minQueueTime, Date maxFinishTime) {
            this.acceptedBuilds = acceptedBuilds;
            this.minQueueTime = minQueueTime;
            this.maxFinishTime = maxFinishTime;
        }
    }

    private boolean shouldBeIgnored(SBuild jobBuild) {
        return jobBuild.isCompositeBuild() || // We ignore composite builds as they do not have any steps
            jobBuild.isPersonal() ||
            jobBuild.getFinishDate() == null; // This can happen in case the build is canceled before it starts
    }

    private JobWebhook createJobWebhook(SBuild jobBuild, String pipelineName, String pipelineID) {
        JobWebhook jobWebhook = new JobWebhook(
                buildName(jobBuild),
                buildURL(jobBuild),
                toRFC3339(jobBuild.getStartDate()),
                toRFC3339(jobBuild.getFinishDate()),
                pipelineID,
                pipelineName,
                buildID(jobBuild),
                getJobStatus(jobBuild),
                queueTimeMs(jobBuild));

        if (!jobBuild.getBuildPromotion().getDependencies().isEmpty()) {
            jobWebhook.setDependenciesIds(getDependenciesIds(jobBuild));
        }

        if (!jobBuild.getTags().isEmpty()) {
            jobWebhook.setTags(jobBuild.getTags());
        }

        getHostInfo(jobBuild).ifPresent(jobWebhook::setHostInfo);
        getErrorInfo(jobBuild).ifPresent(jobWebhook::setErrorInfo);
        return jobWebhook;
    }

    private JobStatus getJobStatus(SBuild jobBuild) {
        Status buildStatus = jobBuild.getBuildStatus();
        if (buildStatus.isSuccessful()) {
            return JobStatus.SUCCESS;
        } else if (buildStatus.isFailed()) {
            return JobStatus.ERROR;
        } else if (jobBuild.getCanceledInfo() != null) {
            return JobStatus.CANCELED;
        }

        throw new IllegalArgumentException("Job status not recognized: " + buildStatus);
    }

    private Optional<HostInfo> getHostInfo(SBuild build) {
        if (build.getAgent().getHostName().isEmpty() && build.getAgent().getHostAddress().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new HostInfo()
                .withHostname(build.getAgent().getHostAddress())
                .withName(build.getAgent().getHostName())
                .withWorkspace(build.getParametersProvider().get(CHECKOUT_DIR_PROPERTY)));
    }

    private Optional<ErrorInfo> getErrorInfo(SBuild build) {
        if (!build.getBuildStatus().isFailed()) {
            return Optional.empty();
        }

        ErrorInfo.ErrorDomain domain = build.isInternalError() ? PROVIDER : USER;
        return build.getFailureReasons().stream()
                .filter(failure -> SUPPORTED_FAILURE_TYPES_MAP.containsKey(failure.getType()))
                .findFirst()
                .map(failure -> new ErrorInfo(failure.getDescription(), SUPPORTED_FAILURE_TYPES_MAP.get(failure.getType()), domain));
    }

    private List<String> getDependenciesIds(SBuild build) {
        return build.getBuildPromotion().getDependencies().stream()
            .filter(dep -> dep.getDependOn().getAssociatedBuild() != null)
            .map(dep -> buildID(dep.getDependOn().getAssociatedBuild()))
            .collect(toList());
    }

    private String buildURL(SBuild build) {
        long buildID = build.getBuildId();
        try {
            String rootURL = buildServer.getRootUrl();
            URI uri = URI.create(rootURL);
            if (uri.getScheme() == null) {
                rootURL = format("%s://%s", DEFAULT_SCHEME, rootURL);
            }

            URL serverRootURL = new URL(rootURL);
            return new URL(serverRootURL, format("/build/%s", buildID)).toString();
        } catch (MalformedURLException e) {
            LOG.warn(format("Failed to build a valid URL for build %s. Falling back to a default empty URL. Exception: %s", buildID, e.getMessage()), e);
            return "";
        }
    }

    private String buildID(SBuild build) {
        // Server ID is included to avoid build ID conflicts on different TC instances within the same org
        return format("%s-%s", serverSettings.getServerUUID(), build.getBuildId());
    }

    /**
     * Logs detailed information from TriggeredBy for debugging purposes.
     */
    private void logTriggeredByInfo(SBuild build, String buildType) {
        try {
            LOG.info(format("--- %s: Build #%d '%s' ---", buildType, build.getBuildId(), buildName(build)));
            LOG.info(format("  Build ID: %s", buildID(build)));
            LOG.info(format("  Is Composite: %s", build.isCompositeBuild()));
            LOG.info(format("  Start Date: %s", build.getStartDate()));
            LOG.info(format("  Finish Date: %s", build.getFinishDate()));
            
            jetbrains.buildServer.serverSide.TriggeredBy triggeredBy = build.getTriggeredBy();
            if (triggeredBy != null) {
                LOG.info("  TriggeredBy Information:");
                LOG.info(format("    AsString: %s", triggeredBy.getAsString()));
                LOG.info(format("    RawTriggeredBy: %s", triggeredBy.getRawTriggeredBy()));
                LOG.info(format("    TriggeredDate: %s", triggeredBy.getTriggeredDate()));
                LOG.info(format("    IsTriggeredByUser: %s", triggeredBy.isTriggeredByUser()));
                LOG.info(format("    User: %s", triggeredBy.getUser() != null ? triggeredBy.getUser().getUsername() : "null"));
                LOG.info(format("    TriggerId: %s", triggeredBy.getTriggerId()));
                LOG.info(format("    Parameters: %s", triggeredBy.getParameters()));
            } else {
                LOG.warn("  TriggeredBy is null!");
            }
        } catch (Exception e) {
            LOG.warn(format("Failed to log TriggeredBy info for build %s: %s", build.getBuildId(), e.getMessage()), e);
        }
    }
}
