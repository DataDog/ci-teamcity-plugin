# Datadog CI TeamCity Integration

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A TeamCity Plugin that provides the integration with the [Datadog CI Visibility](https://www.datadoghq.com/product/ci-cd-monitoring/) product.

# Build

Execute `mvn package` from the project root to build the plugin. The resulting datadog-ci-integration.zip file will be
generated in the 'target' directory.

```
mvn package
```

# Usage

The plugin needs to be configured before it can be used. Please refer to the [TeamCity Setup](https://docs.datadoghq.com/continuous_integration/pipelines/teamcity/) for the Datadog CI Visibility product.

Job queue time (`ci.queue_time`) reports the time TeamCity recorded waiting for an available agent, including agent startup, connectivity verification, and upgrade waits. It excludes snapshot dependency execution, VCS, shared-resource, and build-configuration concurrency waits.

Snapshot dependency wait is reported separately as the numeric job measure `teamcity.dependency_wait_time`, in seconds. Both values are zero when TeamCity recorded no corresponding wait reason. The plugin reads the `queueWaitReason:*` build statistics, available with the supported TeamCity 2021.2 API. These values depend on TeamCity publishing its wait-reason statistics.

