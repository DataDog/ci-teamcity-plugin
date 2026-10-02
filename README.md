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

