package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

public class HostInfo {
    @JsonProperty("hostname") private String hostname;
    @JsonProperty("name") private String name;
    @JsonProperty("workspace") private String workspace;

    public String hostname() {
        return hostname;
    }

    public HostInfo withHostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    public String name() {
        return name;
    }

    public HostInfo withName(String name) {
        this.name = name;
        return this;
    }

    public String workspace() {
        return workspace;
    }

    public HostInfo withWorkspace(String workspace) {
        this.workspace = workspace;
        return this;
    }

    @Override
    public String toString() {
        return "HostInfo{" +
                "hostname='" + hostname + '\'' +
                ", name='" + name + '\'' +
                ", workspace='" + workspace + '\'' +
                '}';
    }
}
