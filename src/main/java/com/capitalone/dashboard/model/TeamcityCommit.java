package com.capitalone.dashboard.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class TeamcityCommit {

    @JsonProperty("version")
    private String id;


    @JsonProperty("comment")
    private String title;
    @JsonProperty("username")
    private String authorName;


    @JsonProperty("username")
    private String committerName;

    @JsonProperty("date")
    private String committedDate;
    @JsonProperty("date")
    private String createdAt;

    @JsonProperty("comment")
    private String message;



//
//    private Integer additions;
//    private Integer deletions;
//    private Integer total;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }



    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }





    public String getCommitterName() {
        return committerName;
    }

    public void setCommitterName(String committerName) {
        this.committerName = committerName;
    }



    public String getCommittedDate() {
        return committedDate;
    }

    public void setCommittedDate(String committedDate) {
        this.committedDate = committedDate;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }



//    public Integer getAdditions() {
//        return additions;
//    }
//
//    public void setAdditions(Integer additions) {
//        this.additions = additions;
//    }
//
//    public Integer getDeletions() {
//        return deletions;
//    }
//
//    public void setDeletions(Integer deletions) {
//        this.deletions = deletions;
//    }
//
//    public Integer getTotal() {
//        return total;
//    }
//
//    public void setTotal(Integer total) {
//        this.total = total;
//    }


//    @JsonProperty("stats")
//    private void unpackStatsInfo(Map<String, Object> statsInfo) {
//        this.additions = (Integer) statsInfo.get("additions");
//        this.deletions = (Integer) statsInfo.get("deletions");
//        this.total = (Integer) statsInfo.get("total");
//    }

}


