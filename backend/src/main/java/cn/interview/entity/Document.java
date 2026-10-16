package cn.interview.entity;

public record Document(String id,String name,String text,boolean truncated,String createdAt) {}
