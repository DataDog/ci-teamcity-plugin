package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.CIEntity;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.HostInfo;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Pipeline.RelatedPipeline;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.SVcsModification;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.dependenciesIds;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.hasChanges;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.isJobBuild;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.isPartialRetry;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.isPipelineBuild;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.queueTimeMs;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain.PROVIDER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain.USER;
import static jetbrains.buildServer.messages.ErrorData.SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE;

@Component
public class CIEntityFactory {

    // We only support these failure types as they usually have more descriptive messages
    // (Otherwise we might encounter things like "exit status code 1")
    private static final Map<String, String> SUPPORTED_FAILURE_TYPES_MAP = new HashMap<String, String>() {{
        put(TC_FAILED_TESTS_TYPE, "Tests Failed");
        put(SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE, "Snapshot Dependencies Failed");
    }};

    protected static final String CHECKOUT_DIR = "system.teamcity.build.checkoutDir";
    private static final Logger LOG = Logger.getInstance(CIEntityFactory.class.getName());

    private final SBuildServer buildServer;
    private final BuildDependenciesManager dependenciesManager;

    public CIEntityFactory(SBuildServer buildServer, BuildDependenciesManager dependenciesManager) {
        this.buildServer = buildServer;
        this.dependenciesManager = dependenciesManager;
    }

    public CIEntity create(SBuild build) {
        CIEntity entity;

        if (isPipelineBuild(build)) {
            entity = createPipelineEntity(build);
        } else if (isJobBuild(build)) {
            entity = createJobEntity(build);
        } else {
            // This should not happen, as we ignore non-eligible builds before reaching this point
            throw new IllegalArgumentException("Could not create entity for build: " + build);
        }

        return entity.withGitInfo(getGitInfo(build));
    }

    private Pipeline createPipelineEntity(SBuild build) {
        boolean isPartialRetry = isPartialRetry(build);
        Pipeline pipeline = new Pipeline(
                build.getFullName(),
                buildURL(build),
                toRFC3339(build.getStartDate()),
                toRFC3339(build.getFinishDate()),
                buildID(build),
                buildID(build),
                isPartialRetry,
                build.getBuildStatus().isSuccessful() ? Pipeline.PipelineStatus.SUCCESS : Pipeline.PipelineStatus.ERROR);

        if (isPartialRetry && build.getPreviousFinished() != null) {
            SBuild previousAttempt = build.getPreviousFinished();
            pipeline.setPreviousAttempt(new RelatedPipeline(buildID(previousAttempt), buildURL(previousAttempt)));
        }

        return pipeline;
    }

    private Job createJobEntity(SBuild build) {
        PipelineInfo pipelineInfo = dependenciesManager.getPipelineBuild(build)
                .map(pipelineBuild -> new PipelineInfo(buildID(pipelineBuild), pipelineBuild.getFullName()))
                .orElseThrow(() -> new IllegalArgumentException(format("Could not find pipeline build for job build %s", build)));

        Job job = new Job(
                build.getFullName(),
                buildURL(build),
                toRFC3339(build.getStartDate()),
                toRFC3339(build.getFinishDate()),
                pipelineInfo.id,
                pipelineInfo.name,
                buildID(build),
                build.getBuildStatus().isSuccessful() ? Job.JobStatus.SUCCESS : Job.JobStatus.ERROR,
                queueTimeMs(build));

        if (!build.getBuildPromotion().getDependencies().isEmpty()) {
            job.setDependenciesIds(dependenciesIds(build));
        }

        return job
                .withHostInfo(getHostInfo(build))
                .withErrorInfo(getErrorInfo(build));
    }

    private GitInfo getGitInfo(SBuild build) {
        Optional<SBuild> eligibleBuildOptional = getEligibleBuild(build);
        if (!eligibleBuildOptional.isPresent()) {
            LOG.warn("Could not find change for build: " + build);
            return null;
        }

        SBuild eligibleBuild = eligibleBuildOptional.get();
        SVcsModification change = eligibleBuild.getContainingChanges().get(0);
        String email = getCommitterEmail(change);

        return new GitInfo()
            .withRepositoryURL(change.getVcsRoot().getProperties().get("url"))
            .withDefaultBranch(change.getVcsRoot().getProperty("branch"))
            .withMessage(change.getDescription())
            .withSha(change.getVersion())
            .withCommitterName(change.getUserName())
            .withAuthorName(change.getUserName())
            .withCommitTime(toRFC3339(change.getCommitDate()))
            .withAuthorTime(toRFC3339(change.getVcsDate()))
            .withAuthorEmail(email)
            .withCommitterEmail(email)
            .withBranch(getBranch(eligibleBuild));
    }

    /**
     * Retrieves the first build that contains the related Git changes. On retries-related builds the changes are
     * not present, so we iterate backwards by taking previously finished builds until we find the one with changes.
     */
    private static Optional<SBuild> getEligibleBuild(SBuild build) {
        if (hasChanges(build)) {
            return Optional.of(build);
        }

        // Trying to retrieve changes from previously finished builds
        int count = 0;
        int maxPreviousBuilds = 5;
        SBuild currentBuild = build;
        while (count < maxPreviousBuilds) {
            SFinishedBuild previousBuild = currentBuild.getPreviousFinished();
            if (previousBuild == null) {
                break;
            }

            if (hasChanges(previousBuild)) {
                return Optional.of(previousBuild);
            }

            currentBuild = previousBuild;
            count++;
        }

        return Optional.empty();
    }

    private String getBranch(SBuild build) {
        if (build.getBranch() == null) {
            //TODO I need to check with TC how to retrieve the branch in these cases
            return "";
        }

        return build.getBranch().getDisplayName();
    }

    private String getCommitterEmail(SVcsModification change) {
        //TODO I need to check with TeamCity how to properly get emails for committers, as there are a bunch of
        // different ways and most of them return null values (as committers.get(0).getEmail()).
        // For now I'm using the username as it's the email.
        ArrayList<SUser> committers = new ArrayList<>(change.getCommitters());
        if (committers.isEmpty()) {
            LOG.error("Could not find committers for change: " + change);
            return "";
        }

        return committers.get(0).getUsername();
    }

    @Nullable
    private HostInfo getHostInfo(SBuild build) {
        if (build.getAgent().getHostName().isEmpty() && build.getAgent().getHostAddress().isEmpty()) {
            return null;
        }

        return new Job.HostInfo()
                .withHostname(build.getAgent().getHostAddress())
                .withName(build.getAgent().getHostName())
                .withWorkspace(build.getParametersProvider().get(CHECKOUT_DIR));
    }

    @Nullable
    private ErrorInfo getErrorInfo(SBuild build) {
        if (!build.getBuildStatus().isFailed()) {
            return null;
        }

        ErrorDomain domain = build.isInternalError() ? PROVIDER : USER;
        return build.getFailureReasons().stream()
                .filter(failure -> SUPPORTED_FAILURE_TYPES_MAP.containsKey(failure.getType()))
                .findFirst()
                .map(failure -> new ErrorInfo(failure.getDescription(), SUPPORTED_FAILURE_TYPES_MAP.get(failure.getType()), domain))
                .orElse(null);
    }

    private String buildURL(SBuild build) {
        return format("%s/build/%s", buildServer.getRootUrl(), build.getBuildId());
    }

    private static class PipelineInfo {
        private final String id;
        private final String name;

        private PipelineInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
