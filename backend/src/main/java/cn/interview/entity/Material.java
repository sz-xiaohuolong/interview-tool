package cn.interview.entity;

import java.util.List;

public record Material(String id,String kind,String name,String content,String createdAt,String updatedAt,List<Document> documents) {}
