package jetbrains.buildServer.com.datadog.teamcity.plugin.model;

import com.google.common.annotations.VisibleForTesting;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;

import java.util.Date;

/**
 * This is our wrapper around a TeamCity build as it's tricky to create new builds of those type during tests.
 */
public class DatadogBuild {

    private final long id;
    private final String name;
    private final Status status;
    private final boolean isComposite;
    private final String projectID;
    private final Date startDate;
    private final Date finishDate;

    @VisibleForTesting
    public DatadogBuild(long id, String name, Status status, boolean isComposite, String projectID, Date startDate, Date finishDate) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.isComposite = isComposite;
        this.projectID = projectID;
        this.startDate = startDate;
        this.finishDate = finishDate;
    }

    public static DatadogBuild fromBuild(SBuild build) {
        return new DatadogBuild(build.getBuildId(), build.getFullName(), build.getBuildStatus(),
                build.isCompositeBuild(), build.getProjectId(), build.getStartDate(), build.getFinishDate());
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Status status() {
        return status;
    }

    public boolean isComposite() {
        return isComposite;
    }

    public String projectID() {
        return projectID;
    }

    public Date startDate() {
        return startDate;
    }

    public Date finishDate() {
        return finishDate;
    }
}
