package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.BuildUtils.isPipelineBuild;

@Component
public class BuildDependenciesManager {

    /**
     * Retrieves the related pipeline build for a job build by traversing its dependents until a composite build with
     * 0 dependents is reached. {@link BuildPromotion} is used underlying as it's not null even when the build is not started yet.
     */
    public Optional<SBuild> getPipelineBuild(SBuild build) {
        Queue<BuildPromotion> buildsQueue = new LinkedList<>();
        buildsQueue.add(build.getBuildPromotion());

        //TODO implement cache to improve performance (CIAPP-5380)
        while (!buildsQueue.isEmpty()) {
            BuildPromotion currentBuild = buildsQueue.remove();
            if (isPipelineBuild(currentBuild)) {
                // We can be sure that the build associated with the promotion is running (and it's not null)
                // as composite builds start when the first dependency starts
                return Optional.of(currentBuild.getAssociatedBuild());
            }

            currentBuild.getDependedOnMe().forEach(buildDependency -> buildsQueue.add(buildDependency.getDependent()));
        }

        return Optional.empty();
    }
}
