package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.CommitRepository;
import com.capitalone.dashboard.util.Supplier;
import com.mongodb.util.JSON;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Component
public class DefaultTeamcityClient implements TeamcityClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTeamcityClient.class);

    private final TeamcitySettings settings;
    private final RestOperations rest;
    private final CommitRepository commitRepository;
    private final PipelineCommitProcessor pipelineCommitProcessor;


    private static final String PROJECT_API_URL_SUFFIX = "app/rest/projects";

    private static final String BUILD_DETAILS_URL_SUFFIX = "app/rest/builds";

    private static final String BUILD_TYPE_DETAILS_URL_SUFFIX = "app/rest/buildTypes";



    @Autowired
    public DefaultTeamcityClient(TeamcitySettings teamcitySettings,
                                 Supplier<RestOperations> restOperationsSupplier,
                                 CommitRepository commitRepository,
                                 PipelineCommitProcessor pipelineCommitProcessor) {
        this.settings = teamcitySettings;
        this.rest = restOperationsSupplier.get();
        this.commitRepository = commitRepository;
        this.pipelineCommitProcessor = pipelineCommitProcessor;

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


            List<PipelineCommit> allPipelineCommits = new ArrayList<>();
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
                Long time = getTimeInMillis(dateInString);

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

                long deployTimeToConsider = getTime(jsonBuild, "finished_at");
//TODO
//                PipelineCommit pipelineCommit = getPipelineCommit(application, buildJson, new JSONObject(),
//                        time == 0 ? getTime(jsonBuild, "created_at") : time);
//                if (pipelineCommit == null) {
//                    continue;
//                }
//
//                //If commit doesn't exist, add it
//                if (allPipelineCommits.stream().noneMatch(pc -> pc.getScmRevisionNumber().equalsIgnoreCase(pipelineCommit.getScmRevisionNumber()))) {
//                    allPipelineCommits.add(pipelineCommit);
//                } else {
//                    //If the incoming pipelineCommit has a smaller timestamp, remove the original one and add the incoming one
//                    Optional<PipelineCommit> existingPipelineCommit = allPipelineCommits.stream().filter(pc ->
//                            pc.getScmRevisionNumber().equalsIgnoreCase(pipelineCommit.getScmRevisionNumber()) &&
//                                    pc.getTimestamp() > pipelineCommit.getTimestamp()).findFirst();
//                    if (existingPipelineCommit.isPresent()) {
//                        PipelineCommit existingPc = existingPipelineCommit.get();
//                        LOGGER.info("Replacing timestamp {} with {} for commit {}", existingPc.getTimestamp(),
//                                pipelineCommit.getTimestamp(),
//                                existingPc.getScmRevisionNumber());
//                        allPipelineCommits.remove(existingPc);
//                        allPipelineCommits.add(pipelineCommit);
//                    }
//                }
//                allPipelineCommits.add(pipelineCommit);
            }
//            pipelineCommitProcessor.processPipelineCommits(allPipelineCommits,
//                    application);
        } catch (HttpClientErrorException | HygieiaException hce) {
            LOGGER.error("http client exception loading build details", hce);
        }

        return environmentStatuses;

    }

    private PipelineCommit getPipelineCommit(TeamcityApplication application, JSONObject deployableObject, JSONObject environmentObject, long timestamp) throws ParseException {
        application.setEnvironment(str(environmentObject, "name"));
        JSONArray lastChanges = (JSONArray) ((JSONObject) deployableObject.get("lastChanges")).get("change");
        JSONObject commitsObject = (JSONObject) lastChanges.stream().findFirst().orElse(new JSONObject());

        String lastCommitID = (String) commitsObject.get("version");
        List<Commit> matchedCommits = commitRepository.findByScmRevisionNumber(lastCommitID);
        Commit newCommit;
        if (matchedCommits != null && matchedCommits.size() > 0) {
            newCommit = matchedCommits.get(0);
        } else {
            newCommit = getCommit(((Long)commitsObject.get("id")).toString(), application.getInstanceUrl(), application.getApplicationId());
        }
        if (newCommit == null) {
            return null;
        }
        return new PipelineCommit(newCommit, timestamp);
    }


    private Commit getCommit(String commitId, String instanceUrl, String applicationId) throws ParseException {

        String url = joinURL(instanceUrl, new String[]{"app/rest/changes", String.format("id:%s", commitId)});
        final String apiKey = settings.getProjectKey(applicationId);
        TeamcityCommit teamcityCommit = makeCommitRestCall(url, apiKey);
        CommitType commitType = CommitType.New; // CommitType.merge

//        TeamcityCommit teamcityCommit = response.getBody();
        if (teamcityCommit == null) {
            return null;
        }
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddThhmmss");
            Date parsedDate = dateFormat.parse(teamcityCommit.getCreatedAt());
            java.sql.Timestamp timestamp = new java.sql.Timestamp(parsedDate.getTime());
            return null;
         //TODO  return getCommit(teamcityCommit, timestamp, commitType);

        } catch(Exception e) { //this generic but you can control another types of exception
            // look the origin of excption
            return null;
        }

    }


    private Commit getCommit(TeamcityCommit teamcityCommit, long timestamp, CommitType commitType) {
        Commit commit = new Commit();
        commit.setTimestamp(System.currentTimeMillis());
//        commit.setScmUrl(repo_url);
//        commit.setScmBranch(teamcityCommit.getLastPipeline().getRef());
        commit.setScmRevisionNumber(teamcityCommit.getId());
        commit.setScmAuthor(teamcityCommit.getAuthorName());
        commit.setScmCommitLog(teamcityCommit.getMessage());
        commit.setScmCommitTimestamp(timestamp);
        commit.setNumberOfChanges(1);
//        commit.setScmParentRevisionNumbers(teamcityCommit.getParentIds());
        commit.setType(commitType);
        return commit;
    }

    private TeamcityCommit makeCommitRestCall(String url, String apiKey) throws ParseException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>("body", headers);



        ResponseEntity<String> jsonString = rest.exchange(url, HttpMethod.GET,
                entity, String.class);

        JSONParser parser = new JSONParser();
        JSONObject buildJson = (JSONObject) parser.parse(jsonString.getBody());

        TeamcityCommit commit = new TeamcityCommit();
        commit.setAuthorName((String) buildJson.get("username"));
        commit.setCommittedDate((String)buildJson.get("date"));
        commit.setCreatedAt((String)buildJson.get("date"));
        commit.setId((String)buildJson.get("version"));
        commit.setMessage((String)buildJson.get("comment"));
        commit.setTitle((String)buildJson.get("message"));
        return commit;

    }

    private long getTime(JSONObject buildJson, String jsonField) {

        String dateToConsider = getString(buildJson, jsonField);
        if (dateToConsider != null) {
            return Instant.from(DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz")
                    .parse(getString(buildJson, jsonField))).toEpochMilli();
        } else {
            return 0L;
        }
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

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + settings.getApiKeys().stream().findFirst().orElse(""));
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<String> entity = new HttpEntity<>("body", headers);


            return rest.exchange(sUrl, HttpMethod.GET, entity, String.class);
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


