package com.capitalone.dashboard.model;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension of Collector that stores current build server configuration.
 */
public class TeamcityCollector extends Collector {
    private List<String> teamcityServers = new ArrayList<>();
    private List<String> niceNames = new ArrayList<>();

    public List<String> getDeployServers() {
        return teamcityServers;
    }

    public List<String> getNiceNames() {
        return niceNames;
    }

    public static TeamcityCollector prototype(List<String> servers, List<String> niceNames) {
        TeamcityCollector protoType = new TeamcityCollector();
        protoType.setName("TeamcityDeployment");
        protoType.setCollectorType(CollectorType.Deployment);
        protoType.setOnline(true);
        protoType.setEnabled(true);
        protoType.getDeployServers().addAll(servers);
        if (!CollectionUtils.isEmpty(niceNames)) {
            protoType.getNiceNames().addAll(niceNames);
        }

        if (!CollectionUtils.isEmpty(niceNames)) {
            protoType.getNiceNames().addAll(niceNames);
        }

        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put(TeamcityApplication.INSTANCE_URL,"");
        allOptions.put(TeamcityApplication.APP_NAME,"");
        allOptions.put(TeamcityApplication.APP_ID, "");
        protoType.setAllFields(allOptions);

        Map<String, Object> uniqueOptions = new HashMap<>();
        uniqueOptions.put(TeamcityApplication.INSTANCE_URL,"");
        uniqueOptions.put(TeamcityApplication.APP_NAME,"");
        protoType.setUniqueFields(uniqueOptions);
        return protoType;
    }
}
