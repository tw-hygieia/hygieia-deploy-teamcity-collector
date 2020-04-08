package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class DefaultTeamcityClient implements TeamcityClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTeamcityClient.class);

    private final TeamcitySettings settings;
    private final RestOperations rest;

    private static final String PROJECT_API_URL_SUFFIX = "app/rest/projects";

    private static final String BUILD_DETAILS_URL_SUFFIX = "app/rest/builds";

    private static final String BUILD_TYPE_DETAILS_URL_SUFFIX = "app/rest/buildTypes";

    @Autowired
    public DefaultTeamcityClient(TeamcitySettings teamcitySettings,
                                 Supplier<RestOperations> restOperationsSupplier) {
        this.settings = teamcitySettings;
        this.rest = restOperationsSupplier.get();
    }

    @Override
    public List<TeamcityApplication> getApplications(String instanceUrl) {
        LOGGER.debug("Enter getApplications");
        List<TeamcityApplication> applications = new ArrayList<>();
        for (String projectID : settings.getProjectIds()) {
            try {
                String url = joinURL(instanceUrl, new String[]{PROJECT_API_URL_SUFFIX + "/id:" + projectID});
                ResponseEntity<String> responseEntity = makeRestCall(url);
                if (responseEntity == null) {
                    break;
                }
                String returnJSON = responseEntity.getBody();
                if (StringUtils.isEmpty(returnJSON)) {
                    break;
                }
                JSONParser parser = new JSONParser();
                try {
                    JSONObject object = (JSONObject) parser.parse(returnJSON);
                    JSONObject subProjectsObject = (JSONObject) object.get("projects");
                    JSONArray subProjects = getJsonArray(subProjectsObject, "project");
                    JSONObject buildTypesObject = (JSONObject) object.get("buildTypes");
                    JSONArray buildTypes = getJsonArray(buildTypesObject, "buildType");
                    if (subProjects.size() == 0 && buildTypes.size() == 0) {
                        break;
                    }
                    if (subProjects.size() > 0) {
                        for (Object subProject : subProjects) {
                            JSONObject jsonSubProject = (JSONObject) subProject;
                            final String subProjectID = getString(jsonSubProject, "id");
                            url = joinURL(instanceUrl, new String[]{PROJECT_API_URL_SUFFIX + "/id:" + subProjectID});
                            responseEntity = makeRestCall(url);
                            if (responseEntity == null) {
                                break;
                            }
                            returnJSON = responseEntity.getBody();
                            if (StringUtils.isEmpty(returnJSON)) {
                                break;
                            }
                            parser = new JSONParser();
                            object = (JSONObject) parser.parse(returnJSON);
                            buildTypesObject = (JSONObject) object.get("buildTypes");
                            buildTypes = getJsonArray(buildTypesObject, "buildType");
                            constructApplication(applications, buildTypes, projectID, instanceUrl);
                        }
                    } else {
                        constructApplication(applications, buildTypes, projectID, instanceUrl);
                    }

                } catch (ParseException e) {
                    LOGGER.error("Parsing jobs details on instance: " + instanceUrl, e);
                }
            } catch (RestClientException rce) {
                LOGGER.error("client exception loading jobs details", rce);
                throw rce;
            } catch (URISyntaxException e1) {
                LOGGER.error("wrong syntax url for loading jobs details", e1);
            }
        }
        return applications;
    }

    private void constructApplication(List<TeamcityApplication> applications, JSONArray buildTypes, String projectID, String instanceUrl) {
        for (Object buildType : buildTypes) {
            JSONObject jsonBuildType = (JSONObject) buildType;
            final String buildTypeID = getString(jsonBuildType, "id");
            try {
                if (isDeploymentBuildType(buildTypeID, instanceUrl)) {
                    LOGGER.debug("Process projectName " + projectID);
                    TeamcityApplication application = new TeamcityApplication();
                    application.setInstanceUrl(instanceUrl);
                    application.setApplicationName(projectID);
                    application.setApplicationId(projectID);
                    applications.add(application);
                    break;
                }
            } catch (URISyntaxException e) {
                LOGGER.error("wrong syntax url for loading jobs details", e);
            } catch (ParseException e) {
                LOGGER.error("Parsing jobs details on instance: " + instanceUrl, e);
            }
        }
    }

    private Boolean isDeploymentBuildType(String buildTypeID, String instanceUrl) throws URISyntaxException, ParseException {
        try {
            String buildTypesUrl = joinURL(instanceUrl, new String[]{String.format("%s/id:%s", BUILD_TYPE_DETAILS_URL_SUFFIX, buildTypeID)});
            LOGGER.info("isDeploymentBuildType Fetching build types details for {}", buildTypesUrl);
            ResponseEntity<String> responseEntity = makeRestCall(buildTypesUrl);
            String returnJSON = responseEntity.getBody();
            if (StringUtils.isEmpty(returnJSON)) {
                return false;
            }
            JSONParser parser = new JSONParser();
            JSONObject object = (JSONObject) parser.parse(returnJSON);

            if (object.isEmpty()) {
                return false;
            }
            JSONObject buildTypesObject = (JSONObject) object.get("settings");
            JSONArray properties = getJsonArray(buildTypesObject, "property");
            if (properties.size() == 0) {
                return false;
            }
            for (Object property : properties) {
                JSONObject jsonProperty = (JSONObject) property;
                String propertyName = jsonProperty.get("name").toString();
                if (!propertyName.equals("buildConfigurationType")) continue;
                String propertyValue = jsonProperty.get("value").toString();
                return propertyValue.equals("DEPLOYMENT");
            }
        } catch (HttpClientErrorException hce) {
            LOGGER.error("http client exception loading build details", hce);
        }
        return false;
    }

    @Override
    public List<Environment> getEnvironments(TeamcityApplication application) {
        List<Environment> environments = new ArrayList<>();
        try {
            String url = joinURL(application.getInstanceUrl(), new String[]{PROJECT_API_URL_SUFFIX + "/id:" + application.getApplicationId()});
            ResponseEntity<String> responseEntity = makeRestCall(url);
            if (responseEntity == null) {
                return Collections.emptyList();
            }
            String returnJSON = responseEntity.getBody();
            if (StringUtils.isEmpty(returnJSON)) {
                return Collections.emptyList();
            }
            JSONParser parser = new JSONParser();
            try {
                JSONObject object = (JSONObject) parser.parse(returnJSON);
                JSONObject subProjectsObject = (JSONObject) object.get("projects");
                JSONArray subProjects = getJsonArray(subProjectsObject, "project");
                JSONObject buildTypesObject = (JSONObject) object.get("buildTypes");
                JSONArray buildTypes = getJsonArray(buildTypesObject, "buildType");
                if (subProjects.size() == 0 && buildTypes.size() == 0) {
                    return Collections.emptyList();
                }
                if (subProjects.size() > 0) {
                    for (Object subProject : subProjects) {
                        JSONObject jsonSubProject = (JSONObject) subProject;
                        final String subProjectID = getString(jsonSubProject, "id");
                        url = joinURL(application.getInstanceUrl(), new String[]{PROJECT_API_URL_SUFFIX + "/id:" + subProjectID});
                        responseEntity = makeRestCall(url);
                        if (responseEntity == null) {
                            break;
                        }
                        returnJSON = responseEntity.getBody();
                        if (StringUtils.isEmpty(returnJSON)) {
                            break;
                        }
                        parser = new JSONParser();
                        object = (JSONObject) parser.parse(returnJSON);
                        buildTypesObject = (JSONObject) object.get("buildTypes");
                        buildTypes = getJsonArray(buildTypesObject, "buildType");
                        constructEnvironment(environments, application, buildTypes);
                    }
                } else {
                    constructEnvironment(environments, application, buildTypes);
                }
            } catch (ParseException e) {
                LOGGER.error("Parsing jobs details on instance: " + application.getInstanceUrl(), e);
            }
        } catch (RestClientException rce) {
            LOGGER.error("client exception loading jobs details", rce);
            throw rce;
        } catch (URISyntaxException e1) {
            LOGGER.error("wrong syntax url for loading jobs details", e1);
        }

        return environments;
    }

    private void constructEnvironment(List<Environment> environments, TeamcityApplication application, JSONArray buildTypes) {
        for (Object buildType : buildTypes) {
            JSONObject jsonBuildType = (JSONObject) buildType;
            final String buildTypeID = getString(jsonBuildType, "id");
            try {
                if (isDeploymentBuildType(buildTypeID, application.getInstanceUrl())) {
                    String buildTypesUrl = joinURL(application.getInstanceUrl(), new String[]{String.format("%s/id:%s", BUILD_TYPE_DETAILS_URL_SUFFIX, buildTypeID)});
                    LOGGER.info("Fetching build types details for {}", buildTypesUrl);
                    ResponseEntity<String> responseEntity;
                    responseEntity = makeRestCall(buildTypesUrl);
                    String returnJSON = responseEntity.getBody();
                    if (StringUtils.isEmpty(returnJSON)) {
                        break;
                    }
                    JSONParser parser = new JSONParser();
                    JSONObject object = (JSONObject) parser.parse(returnJSON);
                    if (object.isEmpty()) {
                        break;
                    }
                    environments.add(new Environment(str(object, "id"), str(
                            object, "name")));
                }
            } catch (URISyntaxException e) {
                LOGGER.error("wrong syntax url for loading jobs details", e);
            } catch (ParseException e) {
                LOGGER.error("Parsing jobs details on instance: " + application.getInstanceUrl(), e);
            }
        }
    }

    private List<TeamcityEnvResCompData> getBuildDetailsForTeamcityProjectPaginated(TeamcityApplication application, Environment environment, int startCount, int buildsCount) throws URISyntaxException, ParseException {
        List<TeamcityEnvResCompData> environmentStatuses = new ArrayList<>();
        try {
            String allBuildsUrl = joinURL(application.getInstanceUrl(), new String[]{BUILD_DETAILS_URL_SUFFIX});
            LOGGER.info("Fetching builds for project {}", allBuildsUrl);
            String url = joinURL(allBuildsUrl, new String[]{String.format("?locator=buildType:%s,count:%d,start:%d", environment.getId(), buildsCount, startCount)});
            ResponseEntity<String> responseEntity = makeRestCall(url);
            String returnJSON = responseEntity.getBody();
            if (StringUtils.isEmpty(returnJSON)) {
                return Collections.emptyList();
            }
            JSONParser parser = new JSONParser();
            JSONObject object = (JSONObject) parser.parse(returnJSON);

            if (object.isEmpty()) {
                return Collections.emptyList();
            }
            JSONArray jsonBuilds = getJsonArray(object, "build");
            for (Object build : jsonBuilds) {
                JSONObject jsonBuild = (JSONObject) build;
                // A basic Build object. This will be fleshed out later if this is a new Build.
                String buildID = jsonBuild.get("id").toString();
                LOGGER.debug(" buildNumber: " + buildID);


                if (!isDeployed(jsonBuild.get("status").toString())) continue;

                TeamcityEnvResCompData deployData = new TeamcityEnvResCompData();

                deployData.setCollectorItemId(application.getId());
                deployData.setEnvironmentName(environment.getName());

                deployData.setComponentID(buildID);
                deployData.setComponentName(application.getApplicationName());
                deployData.setDeployed(true);
                deployData.setAsOfDate(System.currentTimeMillis());
                deployData.setOnline(true);
                deployData.setResourceName("teamcity-runner");
                environmentStatuses.add(deployData);
            }
        } catch (HttpClientErrorException hce) {
            LOGGER.error("http client exception loading build details", hce);
        }
        return environmentStatuses;

    }

    private boolean isDeployed(String deployStatus) {
        //Skip deployments that are simply "created" or "cancelled".
        //Created deployments are never triggered. So there is no point in considering them
        return deployStatus != null && !deployStatus.isEmpty() && deployStatus.equalsIgnoreCase("success");
    }

    // Called by DefaultEnvironmentStatusUpdater
//    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts") // agreed, this method needs refactoring.
    @Override
    public List<TeamcityEnvResCompData> getEnvironmentResourceStatusData(
            TeamcityApplication application, Environment environment) {
        List<TeamcityEnvResCompData> allComponents = new ArrayList<>();
        int startCount = 0;
        int buildsCount = 100;
        while (true) {
            List<TeamcityEnvResCompData> components = null;
            try {
                components = getBuildDetailsForTeamcityProjectPaginated(application, environment, startCount, buildsCount);
            } catch (URISyntaxException | ParseException e) {
                e.printStackTrace();
            }
            if (Objects.requireNonNull(components).isEmpty()) {
                break;
            }
            allComponents.addAll(components);
            startCount += 100;
        }
        return allComponents;
    }


    @SuppressWarnings("PMD")
    protected ResponseEntity<String> makeRestCall(String sUrl) throws URISyntaxException {
        LOGGER.debug("Enter makeRestCall " + sUrl);
        URI thisuri = URI.create(sUrl);
        String userInfo = thisuri.getUserInfo();

        //get userinfo from URI or settings (in spring properties)
        if (StringUtils.isEmpty(userInfo)) {
            List<String> servers = this.settings.getServers();
            List<String> usernames = this.settings.getUsernames();
            List<String> apiKeys = this.settings.getApiKeys();
            if (org.apache.commons.collections.CollectionUtils.isNotEmpty(servers) && org.apache.commons.collections.CollectionUtils.isNotEmpty(usernames) && org.apache.commons.collections.CollectionUtils.isNotEmpty(apiKeys)) {
                boolean exactMatchFound = false;
                for (int i = 0; i < servers.size(); i++) {
                    if ((servers.get(i) != null)) {
                        String domain1 = getDomain(sUrl);
                        String domain2 = getDomain(servers.get(i));
                        if (StringUtils.isNotEmpty(domain1) && StringUtils.isNotEmpty(domain2) && Objects.equals(domain1, domain2)
                                && getPort(sUrl) == getPort(servers.get(i))) {
                            exactMatchFound = true;
                        }
                        if (exactMatchFound && (i < usernames.size()) && (i < apiKeys.size())
                                && (StringUtils.isNotEmpty(usernames.get(i))) && (StringUtils.isNotEmpty(apiKeys.get(i)))) {
                            userInfo = usernames.get(i) + ":" + apiKeys.get(i);
                        }
                        if (exactMatchFound) {
                            break;
                        }
                    }
                }
                if (!exactMatchFound) {
                    LOGGER.warn("Credentials for the following url was not found. This could happen if the domain/subdomain/IP address "
                            + "in the build url returned by Teamcity and the Teamcity instance url in your Hygieia configuration do not match: "
                            + "\"" + sUrl + "\"");
                }
            }
        }
        // Basic Auth only.
        if (StringUtils.isNotEmpty(userInfo)) {
            return rest.exchange(thisuri, HttpMethod.GET,
                    new HttpEntity<>(createHeaders(userInfo)),
                    String.class);
        } else {
            return rest.exchange(thisuri, HttpMethod.GET, null,
                    String.class);
        }

    }

    private String getDomain(String url) throws URISyntaxException {
        URI uri = new URI(url);
        return uri.getHost();
    }

    private int getPort(String url) throws URISyntaxException {
        URI uri = new URI(url);
        return uri.getPort();
    }

    private JSONArray getJsonArray(JSONObject json, String key) {
        Object array = json.get(key);
        return array == null ? new JSONArray() : (JSONArray) array;
    }

    private String getString(JSONObject json, String key) {
        return (String) json.get(key);
    }

    protected HttpHeaders createHeaders(final String userInfo) {
        byte[] encodedAuth = Base64.encodeBase64(
                userInfo.getBytes(StandardCharsets.US_ASCII));
        String authHeader = "Basic " + new String(encodedAuth);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        return headers;
    }

    private String str(JSONObject json, String key) {
        Object value = json.get(key);
        return value == null ? null : value.toString();
    }

    private long date(JSONObject jsonObject, String key) {
        Object value = jsonObject.get(key);
        return value == null ? 0 : (long) value;
    }

    // join a base url to another path or paths - this will handle trailing or non-trailing /'s
    public static String joinURL(String base, String[] paths) {
        StringBuilder result = new StringBuilder(base);
        Arrays.stream(paths).map(path -> path.replaceFirst("^(\\/)+", "")).forEach(p -> {
            if (result.lastIndexOf("/") != result.length() - 1) {
                result.append('/');
            }
            result.append(p);
        });
        return result.toString();
    }
}


