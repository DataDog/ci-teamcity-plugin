package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static java.util.Collections.singletonList;
import static jetbrains.buildServer.BuildProblemTypes.TC_FAILED_TESTS_TYPE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.JOB;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_CHECKOUT_DIR;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_END_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_FAILURE_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_HOSTNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_NODE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_PIPELINE_NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_QUEUE_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_START_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_API_KEY;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.TEST_DD_SITE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.ErrorInfo.ErrorDomain.PROVIDER;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.Job.JobStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DatadogNotificatorJobProcessingTest extends BaseNotificatorProcessingTest {

    @Test
    public void shouldSendWebhookForStandardJobBuild() {
        // Setup
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).withFullName(DEFAULT_PIPELINE_NAME).build();
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withNumOfDependents(1)
                .build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        datadogNotificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));
        assertThat(jobWebhook.pipelineID()).isEqualTo(String.valueOf(2));
        assertThat(jobWebhook.pipelineName()).isEqualTo(DEFAULT_PIPELINE_NAME);

        assertThat(jobWebhook.name()).isEqualTo(DEFAULT_NAME);
        assertThat(jobWebhook.start()).isEqualTo(toRFC3339(DEFAULT_START_DATE));
        assertThat(jobWebhook.end()).isEqualTo(toRFC3339(DEFAULT_END_DATE));
        assertThat(jobWebhook.url()).isEqualTo("root-url/build/1");
        assertThat(jobWebhook.queueTimeMs()).isEqualTo(DEFAULT_START_DATE.getTime() - DEFAULT_QUEUE_DATE.getTime());
        assertThat(jobWebhook.dependencies()).isNull();

        assertThat(jobWebhook.status()).isEqualTo(SUCCESS);
        assertThat(jobWebhook.errorInfo()).isNull();
        assertThat(jobWebhook.hostInfo()).isNull();
        assertThat(jobWebhook.gitInfo()).isNull();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForJobWithoutPipeline() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withNumOfDependents(1)
                .build();
        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.empty());

        // When
        datadogNotificator.onFinishedBuild(jobBuild);
    }

    @Test
    public void shouldSendWebhookWithNodeInformation() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB).addNodeInformation().build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        datadogNotificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));

        Job.HostInfo hostInfo = jobWebhook.hostInfo();
        assertThat(hostInfo).isNotNull();
        assertThat(hostInfo.hostname()).isEqualTo(DEFAULT_NODE_HOSTNAME);
        assertThat(hostInfo.name()).isEqualTo(DEFAULT_NODE_NAME);
        assertThat(hostInfo.workspace()).isEqualTo(DEFAULT_CHECKOUT_DIR);
    }

    @Test
    public void shouldSendWebhookWithFailureReason() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withStatus(Status.FAILURE)
                .withFailureReason(TC_FAILED_TESTS_TYPE)
                .build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        datadogNotificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));

        ErrorInfo errorInfo = jobWebhook.errorInfo();
        assertThat(errorInfo).isNotNull();
        assertThat(errorInfo.message()).isEqualTo(DEFAULT_FAILURE_MESSAGE);
        assertThat(errorInfo.type()).isEqualTo("Tests Failed");
        assertThat(errorInfo.domain()).isEqualTo(PROVIDER);
    }

    @Test
    public void shouldNotIncludeFailureReasonForUnsupportedTypes() {
        // Setup
        SBuild jobBuild = new MockBuild.Builder(1, JOB)
                .withStatus(Status.FAILURE)
                .withFailureReason("Unsupported type")
                .build();
        SBuild pipelineBuild = new MockBuild.Builder(2, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(1)).thenReturn(jobBuild);
        when(dependenciesManagerMock.getPipelineBuild(jobBuild)).thenReturn(Optional.of(pipelineBuild));

        // When
        datadogNotificator.onFinishedBuild(jobBuild);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(1));
        assertThat(jobWebhook.errorInfo()).isNull();
    }

    @Test
    public void shouldIncludeDependenciesForJob() {
        // Setup
        SBuild jobCompile = new MockBuild.Builder(1, JOB).build();
        SBuild jobTest = new MockBuild.Builder(2, JOB)
                .withDependencies(singletonList(jobCompile))
                .build();
        SBuild pipelineBuild = new MockBuild.Builder(3, PIPELINE).build();

        when(buildsManagerMock.findBuildInstanceById(2)).thenReturn(jobTest);
        when(dependenciesManagerMock.getPipelineBuild(jobTest)).thenReturn(Optional.of(pipelineBuild));

        // When
        datadogNotificator.onFinishedBuild(jobTest);

        // Then
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(datadogClientMock, times(1))
                .sendWebhook(jobCaptor.capture(), eq(TEST_API_KEY), eq(TEST_DD_SITE));

        Job jobWebhook = jobCaptor.getValue();
        assertThat(jobWebhook.id()).isEqualTo(String.valueOf(2));
        assertThat(jobWebhook.dependencies()).isEqualTo(singletonList("1"));
    }
}
