package com.capitalone.dashboard.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bean to hold settings specific to the Teamcity collector.
 */
@Component
@ConfigurationProperties(prefix = "Teamcity")
public class TeamcitySettings {
    private String cron;
    private String token;
    private List<String> usernames = new ArrayList<>();
    private List<String> apiKeys = new ArrayList<>();
    private List<String> servers = new ArrayList<>();
    private List<String> niceNames = new ArrayList<>();
    private String projectIds = "";

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public List<String> getServers() {
        return servers;
    }

    public void setServers(List<String> servers) {
        this.servers = servers;
    }

    public List<String> getNiceNames() {
        return niceNames;
    }

    public void setNiceNames(List<String> niceNames) {
        this.niceNames = niceNames;
    }

    public void setProjectIds(String projectIds) {
        this.projectIds = projectIds;
    }

    public List<String> getProjectIds() {
        return Arrays.asList(projectIds.split(","));
    }

}
