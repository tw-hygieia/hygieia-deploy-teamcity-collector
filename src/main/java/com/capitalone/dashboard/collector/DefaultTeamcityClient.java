package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.Environment;
import com.capitalone.dashboard.model.TeamcityApplication;
import com.capitalone.dashboard.model.TeamcityEnvResCompData;
import com.capitalone.dashboard.util.Supplier;
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
import org.springframework.web.client.RestOperations;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
            JSONArray buildTypes = new JSONArray();
            recursivelyFindBuildTypes(instanceUrl, projectID, buildTypes);
            constructApplication(applications, buildTypes, projectID, instanceUrl);
        }
        return applications;
    }

    private void constructApplication(List<TeamcityApplication> applications, JSONArray buildTypes, String projectID, String instanceUrl) {
        for (Object buildType : buildTypes) {
            JSONObject jsonBuildType = (JSONObject) buildType;
            final String buildTypeID = getId(jsonBuildType);
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
            } catch (ParseException e) {
                LOGGER.error("Parsing jobs details on instance: " + instanceUrl, e);
            }
        }
    }

    private Boolean isDeploymentBuildType(String buildTypeID, String instanceUrl) throws ParseException {
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

    private void recursivelyFindBuildTypes(String instanceUrl, String projectID, JSONArray buildTypes) {
        try {
            String url = joinURL(instanceUrl, new String[]{PROJECT_API_URL_SUFFIX + "/id:" + projectID});
            LOGGER.info("Fetching project details for {}", url);
            ResponseEntity<String> responseEntity = makeRestCall(url);
            if (responseEntity == null) {
                return;
            }
            String returnJSON = responseEntity.getBody();
            if (StringUtils.isEmpty(returnJSON)) {
                return;
            }
            JSONParser parser = new JSONParser();
            JSONObject object = (JSONObject) parser.parse(returnJSON);
            JSONObject subProjectsObject = (JSONObject) object.get("projects");
            JSONArray subProjects = getJsonArray(subProjectsObject, "project");
            JSONObject buildTypesObject = (JSONObject) object.get("buildTypes");
            JSONArray buildType = getJsonArray(buildTypesObject, "buildType");
            if (subProjects.size() == 0 && buildType.size() == 0) {
                return;
            }
            buildTypes.addAll(buildType);
            if (subProjects.size() > 0) {
                for (Object subProject : subProjects) {
                    JSONObject jsonSubProject = (JSONObject) subProject;
                    final String subProjectID = getId(jsonSubProject);
                    recursivelyFindBuildTypes(instanceUrl, subProjectID, buildTypes);
                }
            }
        } catch (ParseException e) {
            LOGGER.error("Parsing jobs details on instance: " + instanceUrl, e);
        }
    }

    @Override
    public List<Environment> getEnvironments(TeamcityApplication application) {
        List<Environment> environments = new ArrayList<>();
        JSONArray buildTypes = new JSONArray();
        recursivelyFindBuildTypes(application.getInstanceUrl(), application.getApplicationId(), buildTypes);
        constructEnvironment(environments, application, buildTypes);
        return environments;
    }

    private void constructEnvironment(List<Environment> environments, TeamcityApplication application, JSONArray buildTypes) {
        for (Object buildType : buildTypes) {
            JSONObject jsonBuildType = (JSONObject) buildType;
            final String buildTypeID = getId(jsonBuildType);
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
            } catch (ParseException e) {
                LOGGER.error("Parsing jobs details on instance: " + application.getInstanceUrl(), e);
            }
        }
    }

    private List<TeamcityEnvResCompData> getBuildDetailsForTeamcityProjectPaginated(TeamcityApplication application, Environment environment, int startCount, int buildsCount) throws ParseException {
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
                String buildURL = String.format("%s/id:%s", allBuildsUrl, buildID);
                responseEntity = makeRestCall(buildURL);
                returnJSON = responseEntity.getBody();
                if (StringUtils.isEmpty(returnJSON)) {
                    return Collections.emptyList();
                }
                parser = new JSONParser();
                JSONObject buildJson = (JSONObject) parser.parse(returnJSON);
                if (!isDeployed(buildJson.get("status").toString())) continue;
                JSONObject triggeredObject = (JSONObject) buildJson.get("triggered");
                String dateInString = triggeredObject.get("date").toString();
                long time = getTimeInMillis(dateInString);

                TeamcityEnvResCompData deployData = new TeamcityEnvResCompData();

                deployData.setCollectorItemId(application.getId());
                deployData.setEnvironmentName(environment.getName());

                deployData.setComponentID(buildID);
                deployData.setComponentName(application.getApplicationName());
                deployData.setDeployed(true);
                deployData.setAsOfDate(time);
                deployData.setOnline(true);
                deployData.setResourceName("teamcity-runner");
                environmentStatuses.add(deployData);
            }
        } catch (HttpClientErrorException hce) {
            LOGGER.error("http client exception loading build details", hce);
        }
        return environmentStatuses;

    }

    private long getTimeInMillis(String startDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String dateWithoutOffset = startDate.substring(0, 15);
        String offset = startDate.substring(15);
        LocalDateTime formattedDateTime = LocalDateTime.parse(dateWithoutOffset, formatter);
        String formattedOffset = offset.substring(0, 3) + ":" + offset.substring(3);
        ZoneOffset zoneOffset = ZoneOffset.of(formattedOffset);
        return formattedDateTime.atOffset(zoneOffset).toEpochSecond() * 1000;
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
            } catch (ParseException e) {
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
    protected ResponseEntity<String> makeRestCall(String sUrl) {
        LOGGER.debug("Enter makeRestCall " + sUrl);
        List<String> apiKeys = settings.getApiKeys();
        if (apiKeys.isEmpty()) {
            return rest.exchange(sUrl, HttpMethod.GET, null, String.class);
        } else {
            //TODO apiKeys need not be an array
            return rest.exchange(sUrl, HttpMethod.GET, new HttpEntity<>(createAuthzHeader(apiKeys.get(0))), String.class);
        }
    }

    private static HttpHeaders createAuthzHeader(final String apiToken) {
        String authHeader = "Bearer " + apiToken;

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        return headers;
    }

    private JSONArray getJsonArray(JSONObject json, String key) {
        Object array = json.get(key);
        return array == null ? new JSONArray() : (JSONArray) array;
    }

    private String getId(JSONObject json) {
        return (String) json.get("id");
    }

    private String str(JSONObject json, String key) {
        Object value = json.get(key);
        return value == null ? null : value.toString();
    }

    // join a base url to another path or paths - this will handle trailing or non-trailing /'s
    public static String joinURL(String base, String[] paths) {
        StringBuilder result = new StringBuilder(base);
        Arrays.stream(paths).map(path -> path.replaceFirst("^(/)+", "")).forEach(p -> {
            if (result.lastIndexOf("/") != result.length() - 1) {
                result.append('/');
            }
            result.append(p);
        });
        return result.toString();
    }
}


