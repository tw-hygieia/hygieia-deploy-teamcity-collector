package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.Environment;
import com.capitalone.dashboard.model.EnvironmentComponent;
import com.capitalone.dashboard.model.EnvironmentStatus;
import com.capitalone.dashboard.model.TeamcityApplication;
import com.capitalone.dashboard.model.TeamcityEnvResCompData;

import java.util.List;

/**
 * Client for fetching information from Teamcity.
 */
public interface TeamcityClient {

    /**
     * Fetches all {@link TeamcityApplication}s for a given instance URL.
     *
     * @param instanceUrl instance URL
     * @return list of {@link TeamcityApplication}s
     */
    List<TeamcityApplication> getApplications(String instanceUrl);

    /**
     * Fetches all {@link Environment}s for a given {@link TeamcityApplication}.
     *
     * @param application a {@link TeamcityApplication}
     * @return list of {@link Environment}s
     */
    List<Environment> getEnvironments(TeamcityApplication application);

    /**
     * Fetches all {@link EnvironmentStatus}es for a given {@link TeamcityApplication} and {@link Environment}.
     *
     * @param application a {@link TeamcityApplication}
     * @param environment an {@link Environment}
     * @return list of {@link EnvironmentStatus}es
     */
    List<TeamcityEnvResCompData> getEnvironmentResourceStatusData(TeamcityApplication application, Environment environment);
}
