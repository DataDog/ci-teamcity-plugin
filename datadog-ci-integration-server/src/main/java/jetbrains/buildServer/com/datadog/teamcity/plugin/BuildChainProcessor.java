package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.ErrorInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.HostInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.JobWebhook.JobStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.PipelineWebhook.PipelineStatus;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Webhook;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildName;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.dependenciesIds;
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

    private final SBuildServer buildServer;
    private final DatadogClient datadogClient;
    private final ProjectHandler projectHandler;
    private final GitInformationExtractor gitInformationExtractor;

    public BuildChainProcessor(SBuildServer buildServer, DatadogClient datadogClient, ProjectHandler projectHandler, GitInformationExtractor gitInformationExtractor) {
        this.buildServer = buildServer;
        this.datadogClient = datadogClient;
        this.projectHandler = projectHandler;
        this.gitInformationExtractor = gitInformationExtractor;
    }

    public void process(SBuild pipelineBuild) {
        ProjectParameters params = projectHandler.getProjectParameters(pipelineBuild);
        List<Webhook> webhooks = createWebhooks(pipelineBuild);

        datadogClient.sendWebhooksAsync(webhooks, params.apiKey(), params.ddSite());
    }

    /**
     * Creates all the webhooks for a build chain. There will be 1 pipeline webhook for the final
     * composite build and <em>N</em> webhooks for the eligible job builds in the chain.
     */
    private List<Webhook> createWebhooks(SBuild pipelineBuild) {
        Optional<GitInfo> gitInformation = gitInformationExtractor.extractGitInfo(pipelineBuild);
        PipelineWebhook pipelineWebhook = createPipelineWebhook(pipelineBuild, gitInformation);

        List<Webhook> webhooks = new ArrayList<>(singletonList(pipelineWebhook));
        webhooks.addAll(createJobWebhooks(pipelineBuild, gitInformation));

        return webhooks;
    }

    private PipelineWebhook createPipelineWebhook(SBuild pipelineBuild, Optional<GitInfo> gitInformation) {
        PipelineWebhook pipelineWebhook = new PipelineWebhook(
            pipelineBuild.getFullName(),
            buildURL(pipelineBuild),
            toRFC3339(pipelineBuild.getStartDate()),
            toRFC3339(pipelineBuild.getFinishDate()),
            buildID(pipelineBuild),
            buildID(pipelineBuild),
            isPartialRetry(pipelineBuild),
            pipelineBuild.getBuildStatus().isSuccessful() ? PipelineStatus.SUCCESS : PipelineStatus.ERROR);

        gitInformation.ifPresent(pipelineWebhook::setGitInfo);
        if (!pipelineBuild.getTags().isEmpty()) {
            pipelineWebhook.setTags(pipelineBuild.getTags());
        }

        return pipelineWebhook;
    }

    private List<JobWebhook> createJobWebhooks(SBuild pipelineBuild, Optional<GitInfo> gitInformation) {
        String pipelineName = buildName(pipelineBuild);
        String pipelineID = buildID(pipelineBuild);
        Date pipelineStartWithOffset = pipelineStartWithOffset(pipelineBuild);

        return pipelineBuild.getBuildPromotion().getAllDependencies().stream()
            .map(BuildPromotion::getAssociatedBuild)
            .filter(Objects::nonNull)
            .filter(build -> !shouldBeIgnored(build, pipelineStartWithOffset))
            .map(job -> createJobWebhook(job, pipelineName, pipelineID, gitInformation))
            .collect(toList());
    }

    private boolean shouldBeIgnored(SBuild jobBuild, Date pipelineStart) {
        return jobBuild.isCompositeBuild() || // We ignore composite builds as they do not have any steps
            jobBuild.isPersonal() ||
            // For partial retries, we ignore jobs started before the pipeline,
            // as they are reused builds which were already sent by previous webhooks
            jobBuild.getStartDate().before(pipelineStart);
    }

    private JobWebhook createJobWebhook(SBuild jobBuild, String pipelineName, String pipelineID, Optional<GitInfo> gitInformation) {
        JobWebhook jobWebhook = new JobWebhook(
                jobBuild.getFullName(),
                buildURL(jobBuild),
                toRFC3339(jobBuild.getStartDate()),
                toRFC3339(jobBuild.getFinishDate()),
                pipelineID,
                pipelineName,
                buildID(jobBuild),
                jobBuild.getBuildStatus().isSuccessful() ? JobStatus.SUCCESS : JobStatus.ERROR,
                queueTimeMs(jobBuild));

        if (!jobBuild.getBuildPromotion().getDependencies().isEmpty()) {
            jobWebhook.setDependenciesIds(dependenciesIds(jobBuild));
        }

        if (!jobBuild.getTags().isEmpty()) {
            jobWebhook.setTags(jobBuild.getTags());
        }

        getHostInfo(jobBuild).ifPresent(jobWebhook::setHostInfo);
        getErrorInfo(jobBuild).ifPresent(jobWebhook::setErrorInfo);
        gitInformation.ifPresent(jobWebhook::setGitInfo);
        return jobWebhook;
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

    private String buildURL(SBuild build) {
        return format("%s/build/%s", buildServer.getRootUrl(), build.getBuildId());
    }
}
