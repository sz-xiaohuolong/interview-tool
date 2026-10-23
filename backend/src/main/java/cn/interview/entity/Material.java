package cn.interview.entity;

import java.util.List;

public record Material(String id,String kind,String name,String content,String createdAt,String updatedAt,List<Document> documents,com.fasterxml.jackson.databind.JsonNode repository,com.fasterxml.jackson.databind.JsonNode jobSource) {
    public Material(String id,String kind,String name,String content,String createdAt,String updatedAt,List<Document> documents,com.fasterxml.jackson.databind.JsonNode repository) {
        this(id,kind,name,content,createdAt,updatedAt,documents,repository,null);
    }
    public Material(String id,String kind,String name,String content,String createdAt,String updatedAt,List<Document> documents) {
        this(id,kind,name,content,createdAt,updatedAt,documents,null,null);
    }
}
