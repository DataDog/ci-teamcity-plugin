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

## Preview CI job logs

To report build logs, add the project configuration parameter `datadog.ci.logs.enabled=true` alongside the existing API key, site, and enabled parameters. Log reporting is disabled by default while the intake is in preview. It is available for `datadoghq.com` and `datad0g.com` sites.

When the final composite build finishes, the plugin sends each eligible job's visible build log to `https://http-intake.logs.<site>/api/v2/cilogs` before sending that job's webhook. Requests contain JSON arrays of at most 1,000 lines and 4 MiB of uncompressed JSON. Individual records stay below 900 KiB; longer messages are split into sequential records. A log upload failure does not prevent the job webhook from being sent.

Empty physical lines are omitted because the intake requires non-empty messages. Each record includes a continuous `line_number` and may include `status`, `section_name`, `teamcity.flow_id`, and `teamcity.message_index`. The section name is the innermost TeamCity log block. TeamCity `NORMAL`, `WARNING`, and `FAILURE`/`ERROR` statuses map to `info`, `warn`, and `error`; `UNKNOWN` is omitted. Timestamps outside the intake's accepted window are omitted so the intake can use its receipt time.
