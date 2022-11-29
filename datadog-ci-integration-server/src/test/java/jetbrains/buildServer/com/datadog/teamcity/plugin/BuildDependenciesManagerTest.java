package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Test;

import java.util.Arrays;
import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

public class BuildDependenciesManagerTest {

    private final BuildDependenciesManager buildDependenciesManager = new BuildDependenciesManager();

    @Test
    public void shouldRetrievePipelineForDirectDependency() {
        // Setup: job build -> pipeline build
        SBuild pipelineBuild = new MockBuild.Builder(2).isComposite().build();
        SBuild jobBuild = new MockBuild.Builder(1)
                .withDependents(singletonList(pipelineBuild))
                .build();

        Optional<SBuild> jobPipeline = buildDependenciesManager.getPipelineBuild(jobBuild);

        assertThat(jobPipeline).hasValue(pipelineBuild);
    }

    @Test
    public void shouldRetrievePipelineForIndirectDependency() {
        //                 |-> test1 -|
        // Setup: compile -|          |-> aggregate (pipeline build)
        //                 |-> test2 -|
        SBuild pipelineBuild = new MockBuild.Builder(4).isComposite().build();
        SBuild testBuild1 = new MockBuild.Builder(3)
                .withDependents(singletonList(pipelineBuild))
                .build();
        SBuild testBuild2 = new MockBuild.Builder(2)
                .withDependents(singletonList(pipelineBuild))
                .build();
        SBuild compileBuild = new MockBuild.Builder(1)
                .withDependents(Arrays.asList(testBuild1, testBuild2))
                .build();

        Optional<SBuild> jobPipeline = buildDependenciesManager.getPipelineBuild(compileBuild);

        assertThat(jobPipeline).hasValue(pipelineBuild);
    }

    @Test
    public void shouldReturnEmptyIfPipelineNotFound() {
        SBuild jobBuild = new MockBuild.Builder(1)
                .withDependents(emptyList())
                .build();

        Optional<SBuild> jobPipeline = buildDependenciesManager.getPipelineBuild(jobBuild);

        assertThat(jobPipeline).isEmpty();
    }
}
