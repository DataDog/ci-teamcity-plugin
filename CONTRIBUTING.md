# Contributing

First of all, thanks for contributing!

This document provides some basic guidelines for contributing to this repository.
To propose improvements, feel free to submit a PR or open an Issue.

## Setup your developer Environment

Before making any contribution to this project, it's important to familiarize yourself with the 
[TeamCity Plugin Development Guide](https://plugins.jetbrains.com/docs/teamcity/getting-started-with-plugin-development.html).
Cloning the project is enough for getting started. As mentioned in the guide above, Java 8 is needed, and Maven version 3.2.x is recommended. 

## Testing your changes locally

We strongly encourage you to write unit tests. Also, is advisable to test your changes in a local TeamCity server by
uploading the plugin with the changes and test that it behaves correctly.

## Submitting Issues

Many great ideas for new features come from the community, and we'd be happy to consider yours!

To share your request, you can open an [issue](https://github.com/DataDog/ci-teamcity-plugin/issues/new)
with the details about what you'd like to see. At a minimum, please provide:

- The goal of the new feature;
- A description of how it might be used or behave;
- Links to any important resources (e.g. Github repos, websites, screenshots,
  specifications, diagrams).

## Found a bug?

You can submit bug reports concerning the Datadog CI Teamcity Integration by
[opening a Github issue](https://github.com/DataDog/ci-teamcity-plugin/issues/new).
At a minimum, please provide:

- A description of the problem;
- Steps to reproduce. This could be a specific build chain configuration on which the problem will arise;
- Expected behavior;
- Actual behavior;
- Errors (with stack traces) or warnings received;
- Any details you can share about your build-chain configuration;

If at all possible, also provide:

- An explanation of what causes the bug and/or how it can be fixed.

## Have a patch?

We welcome code contributions to the plugin, which you can
[submit as a pull request](https://github.com/DataDog/ci-teamcity-plugin/pull/new/main).
Before you submit a PR, make sure that you first create an Issue to explain the
bug or the feature your patch covers, and make sure another Issue or PR doesn't
already exist.

To create a pull request:

1. **Fork the repository** from https://github.com/DataDog/ci-teamcity-plugin;
2. **Make any changes** for your patch;
3. **Write tests** that demonstrate how the feature works or how the bug is fixed;
4. **Update any documentation**, especially for new features;
5. **Submit the pull request** from your fork back to this
   [repository](https://github.com/DataDog/ci-teamcity-plugin).

The pull request will be run through our CI pipeline, and a project member will
review the changes with you. At a minimum, to be accepted and merged, pull
requests must:

- Have a stated goal and detailed description of the changes made;
- Include thorough test coverage and documentation, where applicable;
- Pass all tests on CI;
- Receive at least one approval from a project member of the Datadog Ci Visibility team.

Make sure that your code is clean and readable, and that your commits are small and
atomic, with a proper commit message.
