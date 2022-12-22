/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.util.EventDispatcher;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildName;

@Component
public class DatadogServerAdapter extends BuildServerAdapter {

    private static final Logger LOG = Logger.getInstance(DatadogServerAdapter.class.getName());

    private final BuildsManager buildsManager;
    private final BuildChainProcessor buildChainProcessor;

    public DatadogServerAdapter(EventDispatcher<BuildServerListener> eventListener,
                                BuildsManager buildsManager,
                                BuildChainProcessor buildChainProcessor) {
        this.buildsManager = buildsManager;
        this.buildChainProcessor = buildChainProcessor;

        eventListener.addListener(this);
    }

    @Override
    public void buildFinished(@Nonnull SRunningBuild build) {
        onBuildFinished(build);
    }

    @Override
    public void buildInterrupted(SRunningBuild build) {
        onBuildFinished(build);
    }

    private void onBuildFinished(SRunningBuild build) {
        if (!isLastCompositeBuild(build)) {
            LOG.info(format("Ignoring build with id '%s' and name '%s'", build.getBuildId(), buildName(build)));
            return;
        }

        // At this point, we know it's the final composite build of the chain
        SBuild pipelineBuild = buildsManager.findBuildInstanceById(build.getBuildId());
        if (pipelineBuild == null) {
            // This should not happen, but better to check for it anyway
            LOG.error("The TeamCity server could not find build with ID: " + build.getBuildId());
            return;
        }

        buildChainProcessor.process(pipelineBuild);
    }

    private boolean isLastCompositeBuild(SBuild build) {
        return build.isCompositeBuild() &&
            build.getBuildPromotion().getNumberOfDependedOnMe() == 0 &&
            !build.isPersonal();
    }
}
