package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Component
public class DefaultTeamcityClient implements TeamcityClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTeamcityClient.class);

    private final TeamcitySettings settings;
    private final RestOperations rest;

    private static final String PROJECT_API_URL_SUFFIX = "httpAuth/app/rest/projects";

    private static final String BUILD_DETAILS_URL_SUFFIX = "httpAuth/app/rest/builds";

    private static final String BUILD_TYPE_DETAILS_URL_SUFFIX = "httpAuth/app/rest/buildTypes";

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
        } catch (HttpClientErrorException | HygieiaException hce) {
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
                    final String subProjectID = getString(jsonSubProject, "id");
                    recursivelyFindBuildTypes(instanceUrl, subProjectID, buildTypes);
                }
            }
        } catch (ParseException e) {
            LOGGER.error("Parsing jobs details on instance: " + instanceUrl, e);
        } catch (HygieiaException e) {
            LOGGER.error("Error in calling Teamcity API", e);
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
            } catch (HygieiaException e) {
                LOGGER.error("Error in calling Teamcity API", e);
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
                SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss+0530");
                Date date = df.parse(dateInString);

                TeamcityEnvResCompData deployData = new TeamcityEnvResCompData();

                deployData.setCollectorItemId(application.getId());
                deployData.setEnvironmentName(environment.getName());

                deployData.setComponentID(buildID);
                deployData.setComponentName(application.getApplicationName());
                deployData.setDeployed(true);
                deployData.setAsOfDate(date.getTime());
                deployData.setOnline(true);
                deployData.setResourceName("teamcity-runner");
                environmentStatuses.add(deployData);
            }
        } catch (HttpClientErrorException | HygieiaException | java.text.ParseException hce) {
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
    protected ResponseEntity<String> makeRestCall(String sUrl) throws HygieiaException {
        LOGGER.debug("Enter makeRestCall " + sUrl);
        String teamcityAccess = settings.getCredentials();
        if (StringUtils.isEmpty(teamcityAccess)) {
            return rest.exchange(sUrl, HttpMethod.GET, null, String.class);
        } else {
            String teamcityAccessBase64 = new String(Base64.decodeBase64(teamcityAccess));
            String[] parts = teamcityAccessBase64.split(":");
            if (parts.length != 2) {
                throw new HygieiaException("Invalid Teamcity credentials", HygieiaException.INVALID_CONFIGURATION);
            }
            return rest.exchange(sUrl, HttpMethod.GET, new HttpEntity<>(createHeaders(parts[0], parts[1])), String.class);
        }
    }

    private static HttpHeaders createHeaders(final String userId, final String password) {
        String auth = userId + ':' + password;
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.US_ASCII));
        String authHeader = "Basic " + new String(encodedAuth);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        return headers;
    }

    private JSONArray getJsonArray(JSONObject json, String key) {
        Object array = json.get(key);
        return array == null ? new JSONArray() : (JSONArray) array;
    }

    private String getString(JSONObject json, String key) {
        return (String) json.get(key);
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


