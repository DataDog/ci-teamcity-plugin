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

When the final composite build finishes, the plugin sends each eligible job's visible build log to `https://http-intake.logs.<site>/api/v2/cilogs` before sending that job's webhook. Requests contain JSON arrays of at most 1,000 lines and 4 MiB of uncompressed JSON. Messages longer than 32,768 Unicode code points are split into sequential records, each below 900 KiB. A log upload failure keeps the job pending until its logs are accepted.

Empty physical lines are omitted because the intake requires non-empty messages. Each record includes a continuous `line_number` and may include `status`, `section_name`, `teamcity.flow_id`, and `teamcity.message_index`. The section name is the innermost TeamCity log block. TeamCity `NORMAL`, `WARNING`, and `FAILURE`/`ERROR` statuses map to `info`, `warn`, and `error`; `UNKNOWN` is omitted. Timestamps more than 18 hours old or more than 12 hours in the future are omitted so the intake can use its receipt time. Because logs are uploaded after the final composite build finishes, lines from long-running build chains can lose their original timestamps; `line_number` still preserves their order within each job.

Log reporting runs up to four jobs concurrently. Pending pipeline and job webhooks, together with each job's last accepted line number, are recorded in the TeamCity plugin data directory. Large build chains do not lose logs when workers are busy, and unfinished deliveries resume after a server restart. If intake retries are exhausted, the job stays pending and is retried later; its job webhook is sent only after every log batch is accepted. TeamCity must retain the build log until delivery completes.
