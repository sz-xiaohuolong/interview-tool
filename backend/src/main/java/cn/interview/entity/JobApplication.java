package cn.interview.entity;

/** 求职记录独立保存，不承担资料或面试的生命周期。 */
public record JobApplication(
    String id, String company, String position, String location, String platform, String url, String jd,
    String jobType, String status, String appliedDate, String nextActionDate, String interviewAt,
    String interviewNote, String notes, boolean archived, String createdAt, String updatedAt
) {}
