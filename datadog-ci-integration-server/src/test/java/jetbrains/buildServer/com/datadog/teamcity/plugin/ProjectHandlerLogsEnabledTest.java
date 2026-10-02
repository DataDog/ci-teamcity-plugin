/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class ProjectHandlerLogsEnabledTest {
    @Parameterized.Parameters(name = "disabledValue={0}, enabled={1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
                {null, true},
                {"", false},
                {"true", false},
                {"false", false},
                {" ", false},
                {"anything", false}
        });
    }

    private final String logsDisabledValue;
    private final boolean expectedEnabled;

    public ProjectHandlerLogsEnabledTest(String logsDisabledValue, boolean expectedEnabled) {
        this.logsDisabledValue = logsDisabledValue;
        this.expectedEnabled = expectedEnabled;
    }

    @Test
    public void disablesLogsForAnyConfiguredValue() {
        assertThat(ProjectHandler.isLogsEnabled(logsDisabledValue)).isEqualTo(expectedEnabled);
    }
}
