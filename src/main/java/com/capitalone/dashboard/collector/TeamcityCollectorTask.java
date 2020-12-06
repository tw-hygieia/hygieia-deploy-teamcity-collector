package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import com.google.common.collect.Iterables;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects {@link EnvironmentComponent} and {@link EnvironmentStatus} data from
 * {@link TeamcityApplication}s.
 */
@Component
public class TeamcityCollectorTask extends CollectorTask<TeamcityCollector> {
    @SuppressWarnings({"unused", "PMD.UnusedPrivateField"})
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamcityCollectorTask.class);

    private final TeamcityCollectorRepository teamcityCollectorRepository;
    private final TeamcityApplicationRepository teamcityApplicationRepository;
    private final TeamcityClient teamcityClient;
    private final TeamcitySettings teamcitySettings;
    private final EnvironmentComponentRepository envComponentRepository;
    private final EnvironmentStatusRepository environmentStatusRepository;
    private final ConfigurationRepository configurationRepository;
    private final ComponentRepository dbComponentRepository;

    @Autowired
    public TeamcityCollectorTask(TaskScheduler taskScheduler,
                                 TeamcityCollectorRepository teamcityCollectorRepository,
                                 TeamcityApplicationRepository teamcityApplicationRepository,
                                 EnvironmentComponentRepository envComponentRepository,
                                 EnvironmentStatusRepository environmentStatusRepository,
                                 TeamcitySettings teamcitySettings, TeamcityClient teamcityClient,
                                 ConfigurationRepository configurationRepository,
                                 ComponentRepository dbComponentRepository) {
        super(taskScheduler, "TeamcityDeployment");
        this.teamcityCollectorRepository = teamcityCollectorRepository;
        this.teamcityApplicationRepository = teamcityApplicationRepository;
        this.teamcitySettings = teamcitySettings;
        this.teamcityClient = teamcityClient;
        this.envComponentRepository = envComponentRepository;
        this.environmentStatusRepository = environmentStatusRepository;
        this.dbComponentRepository = dbComponentRepository;
        this.configurationRepository = configurationRepository;
    }

    @Override
    public TeamcityCollector getCollector() {
        Configuration config = configurationRepository.findByCollectorName("TeamcityDeployment");
        if (config != null) {
            config.decryptOrEncrptInfo();
            // TO clear the username and password from existing run and
            // pick the latest
            teamcitySettings.getServers().clear();
            teamcitySettings.getApiKeys().clear();
            for (Map<String, String> teamcityServer : config.getInfo()) {
                teamcitySettings.getServers().add(teamcityServer.get("url"));
                teamcitySettings.getApiKeys().add(teamcityServer.get("password"));
            }
        }
        return TeamcityCollector.prototype(teamcitySettings.getServers(), teamcitySettings.getNiceNames());
    }

    @Override
    public BaseCollectorRepository<TeamcityCollector> getCollectorRepository() {
        return teamcityCollectorRepository;
    }

    @Override
    public String getCron() {
        return teamcitySettings.getCron();
    }

    @Override
    public void collect(TeamcityCollector collector) {
        for (String instanceUrl : collector.getDeployServers()) {

            logBanner(instanceUrl);

            long start = System.currentTimeMillis();

            clean(collector);

            addNewApplications(teamcityClient.getApplications(instanceUrl),
                    collector);
            updateData(enabledApplications(collector, instanceUrl));

            log("Finished", start);
        }
    }

    /**
     * Clean up unused deployment collector items
     *
     * @param collector the {@link TeamcityCollector}
     */
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private void clean(TeamcityCollector collector) {
        deleteUnwantedJobs(collector);
        Set<ObjectId> uniqueIDs = new HashSet<>();
        for (com.capitalone.dashboard.model.Component comp : dbComponentRepository
                .findAll()) {
            if (comp.getCollectorItems() == null || comp.getCollectorItems().isEmpty()) continue;
            List<CollectorItem> itemList = comp.getCollectorItems().get(
                    CollectorType.Deployment);
            if (itemList == null) continue;
            for (CollectorItem ci : itemList) {
                if (ci == null) continue;
                uniqueIDs.add(ci.getId());
            }
        }
        List<TeamcityApplication> appList = new ArrayList<>();
        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        for (TeamcityApplication app : teamcityApplicationRepository.findByCollectorIdIn(udId)) {
            if (app != null) {
                app.setEnabled(uniqueIDs.contains(app.getId()));
                appList.add(app);
            }
        }
        teamcityApplicationRepository.save(appList);
    }

    private void deleteUnwantedJobs(TeamcityCollector collector) {

        List<TeamcityApplication> deleteAppList = new ArrayList<>();
        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        for (TeamcityApplication app : teamcityApplicationRepository.findByCollectorIdIn(udId)) {
            if (!collector.getDeployServers().contains(app.getInstanceUrl()) ||
                    (!app.getCollectorId().equals(collector.getId()))) {
                deleteAppList.add(app);
            }
        }

        teamcityApplicationRepository.delete(deleteAppList);

    }

    private List<EnvironmentComponent> getEnvironmentComponent(List<TeamcityEnvResCompData> dataList, Environment environment) {
        List<EnvironmentComponent> returnList = new ArrayList<>();
        for (TeamcityEnvResCompData data : dataList) {
            EnvironmentComponent component = new EnvironmentComponent();
            component.setComponentName(data.getComponentName());
            component.setCollectorItemId(data.getCollectorItemId());
            component.setComponentVersion(data
                    .getComponentVersion());
            component.setDeployed(data.isDeployed());
            component.setEnvironmentName(data
                    .getEnvironmentName());

            component.setEnvironmentName(environment.getName());
            component.setAsOfDate(data.getAsOfDate());
            returnList.add(component);
        }
        return returnList;
    }

    private List<EnvironmentStatus> getEnvironmentStatus(List<TeamcityEnvResCompData> dataList) {
        List<EnvironmentStatus> returnList = new ArrayList<>();
        for (TeamcityEnvResCompData data : dataList) {
            EnvironmentStatus status = new EnvironmentStatus();
            status.setCollectorItemId(data.getCollectorItemId());
            status.setComponentID(data.getComponentID());
            status.setComponentName(data.getComponentName());
            status.setEnvironmentName(data.getEnvironmentName());
            status.setOnline(data.isOnline());
            status.setResourceName(data.getResourceName());

            returnList.add(status);
        }
        return returnList;
    }

    /**
     * For each {@link TeamcityApplication}, update the current
     * {@link EnvironmentComponent}s and {@link EnvironmentStatus}.
     *
     * @param teamcityApplications list of {@link TeamcityApplication}s
     */
    private void updateData(List<TeamcityApplication> teamcityApplications) {
        for (TeamcityApplication application : teamcityApplications) {
            List<EnvironmentComponent> compList = new ArrayList<>();
            List<EnvironmentStatus> statusList = new ArrayList<>();
            long startApp = System.currentTimeMillis();
            for (Environment environment : teamcityClient
                    .getEnvironments(application)) {
                environment.setName(environment.getName().replace(".","_"));
                List<TeamcityEnvResCompData> combinedDataList = teamcityClient
                        .getEnvironmentResourceStatusData(application,
                                environment);
                compList.addAll(getEnvironmentComponent(combinedDataList, environment));
                statusList.addAll(getEnvironmentStatus(combinedDataList));
            }
            if (!compList.isEmpty()) {
                List<EnvironmentComponent> existingComponents = envComponentRepository
                        .findByCollectorItemId(application.getId());
                envComponentRepository.delete(existingComponents);
                envComponentRepository.save(compList);
            }
            if (!statusList.isEmpty()) {
                List<EnvironmentStatus> existingStatuses = environmentStatusRepository
                        .findByCollectorItemId(application.getId());
                environmentStatusRepository.delete(existingStatuses);
                environmentStatusRepository.save(statusList);
            }

            log(" " + application.getApplicationName(), startApp);
        }
    }

    private List<TeamcityApplication> enabledApplications(
            TeamcityCollector collector, String instanceUrl) {
        return teamcityApplicationRepository.findEnabledApplications(
                collector.getId(), instanceUrl);
    }

    /**
     * Add any new {@link TeamcityApplication}s.
     *
     * @param applications list of {@link TeamcityApplication}s
     * @param collector    the {@link TeamcityCollector}
     */
    private void addNewApplications(List<TeamcityApplication> applications,
                                    TeamcityCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;

        log("All apps", start, applications.size());
        for (TeamcityApplication application : applications) {
            TeamcityApplication existing = findExistingApplication(collector, application);

            String niceName = getNiceName(application, collector);
            if (existing == null) {
                application.setCollectorId(collector.getId());
                application.setEnabled(false);
                application.setDescription(application.getApplicationName());
                if (StringUtils.isNotEmpty(niceName)) {
                    application.setNiceName(niceName);
                }
                try {
                    teamcityApplicationRepository.save(application);
                } catch (org.springframework.dao.DuplicateKeyException ce) {
                    log("Duplicates items not allowed", 0);

                }
                count++;
            } else if (StringUtils.isEmpty(existing.getNiceName()) && StringUtils.isNotEmpty(niceName)) {
                existing.setNiceName(niceName);
                teamcityApplicationRepository.save(existing);
            }

        }
        log("New apps", start, count);
    }

    private TeamcityApplication findExistingApplication(TeamcityCollector collector,
                                                        TeamcityApplication application) {
        return teamcityApplicationRepository.findTeamcityApplication(
                collector.getId(), application.getInstanceUrl(),
                application.getApplicationId());
    }

    private String getNiceName(TeamcityApplication application, TeamcityCollector collector) {
        if (CollectionUtils.isEmpty(collector.getDeployServers())) return "";
        List<String> servers = collector.getDeployServers();
        List<String> niceNames = collector.getNiceNames();
        if (CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(application.getInstanceUrl()) && niceNames.size() > i) {
                return niceNames.get(i);
            }
        }
        return "";
    }

    @SuppressWarnings("unused")
    private boolean changed(EnvironmentStatus status, EnvironmentStatus existing) {
        return existing.isOnline() != status.isOnline();
    }

    @SuppressWarnings("unused")
    private EnvironmentStatus findExistingStatus(
            final EnvironmentStatus proposed,
            List<EnvironmentStatus> existingStatuses) {

        return Iterables.tryFind(existingStatuses,
                existing -> existing.getEnvironmentName().equals(
                        proposed.getEnvironmentName())
                        && existing.getComponentName().equals(
                        proposed.getComponentName())
                        && existing.getResourceName().equals(
                        proposed.getResourceName())).orNull();
    }

    @SuppressWarnings("unused")
    private boolean changed(EnvironmentComponent component,
                            EnvironmentComponent existing) {
        return existing.isDeployed() != component.isDeployed()
                || existing.getAsOfDate() != component.getAsOfDate() || !existing.getComponentVersion().equalsIgnoreCase(component.getComponentVersion());
    }


    @SuppressWarnings("unused")
    private EnvironmentComponent findExistingComponent(
            final EnvironmentComponent proposed,
            List<EnvironmentComponent> existingComponents) {

        return Iterables.tryFind(existingComponents,
                existing -> existing.getEnvironmentName().equals(
                        proposed.getEnvironmentName())
                        && existing.getComponentName().equals(
                        proposed.getComponentName())).orNull();
    }
}

