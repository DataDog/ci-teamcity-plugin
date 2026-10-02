/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.ProjectHandler.ProjectParameters;
import jetbrains.buildServer.com.datadog.teamcity.plugin.logs.LogDeliveryCoordinator;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildHistory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.ServerSettings;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.util.ItemProcessor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class BuildChainProcessorRecoveryTest {
    @Parameterized.Parameters(name = "known: {0}, before first enablement: {1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {{false, false}, {true, false}, {false, true}});
    }

    private final boolean known;
    private final boolean beforeEnablement;

    public BuildChainProcessorRecoveryTest(boolean known, boolean beforeEnablement) {
        this.known = known;
        this.beforeEnablement = beforeEnablement;
    }

    @Test
    public void recoversOnlyCompositesWithoutDeliveryState() {
        SBuildServer server = mock(SBuildServer.class);
        DatadogClient client = mock(DatadogClient.class);
        LogDeliveryCoordinator coordinator = mock(LogDeliveryCoordinator.class);
        ProjectHandler projects = mock(ProjectHandler.class);
        GitInformationExtractor git = mock(GitInformationExtractor.class);
        ServerSettings settings = mock(ServerSettings.class);
        BuildHistory history = mock(BuildHistory.class);
        SFinishedBuild finished = mock(SFinishedBuild.class);
        BuildPromotion promotion = mock(BuildPromotion.class);
        TriggeredBy triggeredBy = mock(TriggeredBy.class);
        Date now = new Date();
        when(server.getHistory()).thenReturn(history);
        when(server.getRootUrl()).thenReturn("http://localhost:8111");
        when(settings.getServerUUID()).thenReturn("server-id");
        when(finished.getBuildId()).thenReturn(17L);
        when(finished.getStartDate()).thenReturn(now);
        when(finished.getFinishDate()).thenReturn(now);
        when(finished.isCompositeBuild()).thenReturn(true);
        when(finished.getBuildPromotion()).thenReturn(promotion);
        when(finished.getBuildStatus()).thenReturn(Status.NORMAL);
        when(finished.getFullName()).thenReturn("Aggregate");
        when(finished.getTriggeredBy()).thenReturn(triggeredBy);
        when(finished.getTags()).thenReturn(Collections.emptyList());
        when(triggeredBy.getParameters()).thenReturn(Collections.emptyMap());
        when(promotion.getAllDependencies()).thenReturn(Collections.emptyList());
        when(projects.isPluginEnabled(finished)).thenReturn(true);
        when(projects.getProjectParameters(finished))
                .thenReturn(new ProjectParameters("key", "datad0g.com", true));
        when(coordinator.isKnown(17L)).thenReturn(known);
        when(coordinator.recoveryStartMillis()).thenReturn(now.getTime() + (beforeEnablement ? 1 : -1));
        when(git.extractGitInfo(finished)).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            ((ItemProcessor<SFinishedBuild>) invocation.getArguments()[0]).processItem(finished);
            return null;
        }).when(history).processEntries(any(ItemProcessor.class));
        BuildChainProcessor processor = new BuildChainProcessor(server, client, coordinator, projects, git, settings);
        ArgumentCaptor<Runnable> recovery = ArgumentCaptor.forClass(Runnable.class);

        processor.scheduleRecovery();
        verify(coordinator).scheduleRecovery(recovery.capture());
        recovery.getValue().run();

        if (known || beforeEnablement) {
            verify(coordinator, never()).enqueue(any(), any(), any(), any());
        } else {
            verify(coordinator).enqueue(eq(finished), eq(Collections.emptyList()), any(), eq("datad0g.com"));
        }
    }
}
