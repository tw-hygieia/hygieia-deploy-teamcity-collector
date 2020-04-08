package com.capitalone.dashboard.repository;

import com.capitalone.dashboard.model.TeamcityApplication;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * Repository for {@link TeamcityApplication}s.
 */
public interface TeamcityApplicationRepository extends BaseCollectorItemRepository<TeamcityApplication> {

    /**
     * Find a {@link TeamcityApplication} by Teamcity instance URL and Teamcity application id.
     *
     * @param collectorId   ID of the {@link com.capitalone.dashboard.model.TeamcityCollector}
     * @param instanceUrl   Teamcity instance URL
     * @param applicationId Teamcity application ID
     * @return a {@link TeamcityApplication} instance
     */
    @Query(value = "{ 'collectorId' : ?0, options.instanceUrl : ?1, options.applicationId : ?2}")
    TeamcityApplication findTeamcityApplication(ObjectId collectorId, String instanceUrl, String applicationId);

    /**
     * Finds all {@link TeamcityApplication}s for the given instance URL.
     *
     * @param collectorId ID of the {@link com.capitalone.dashboard.model.TeamcityCollector}
     * @param instanceUrl Teamcity instance URl
     * @return list of {@link TeamcityApplication}s
     */
    @Query(value = "{ 'collectorId' : ?0, options.instanceUrl : ?1, enabled: true}")
    List<TeamcityApplication> findEnabledApplications(ObjectId collectorId, String instanceUrl);
}
