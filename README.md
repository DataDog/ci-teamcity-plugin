# Datadog CI TeamCity Integration

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A TeamCity Plugin that provides the integration with the [Datadog CI Visibility](https://www.datadoghq.com/product/ci-cd-monitoring/) product.

# Build

Execute `mvn package` from the project root to build the plugin. The resulting datadog-ci-integration.zip file will be
generated in the 'target' directory.

```
mvn package
```

# General Overview
The Datadog CI Integration plugin is implemented as a [TeamCity Server Adapter](https://javadoc.jetbrains.net/teamcity/openapi/current/jetbrains/buildServer/serverSide/BuildServerAdapter.html). When it receives the final 
composite build of a build chain, the plugin does the following steps:
1. Creates a pipeline webhook for the whole build chain.
2. Creates _N_ job webhooks, one for each eligible build in the chain. 
3. Sends these webhooks to the Datadog webhooks intake.

In order to correctly send webhooks to Datadog, the plugin requires two project-level parameters to be present:
- `datadog.ci.api.key`: That represents the client Datadog API Key.
- `datadog.ci.site`: That represents one of Datadog's datacenters. 

More information can be found on the 'Getting Started' guide for TeamCity for the Datadog CI Visibility product.
