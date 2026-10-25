package cn.interview.entity.jobsearch;

/** 只含业务字段，不携带浏览器会话或招聘联系人。 */
public record JobRecord(String key,String source,String sourceId,String title,String company,String city,String location,
        String url,String salaryText,Double salaryMinK,Double salaryMaxK,Integer salaryMonths,
        String experienceText,String educationText,String description,String fetchedAt,String detailFetchedAt,String publishedAt,
        JobType jobType) {
    public JobRecord(String key,String source,String sourceId,String title,String company,String city,String location,
            String url,String salaryText,Double salaryMinK,Double salaryMaxK,Integer salaryMonths,
            String experienceText,String educationText,String description,String fetchedAt,String detailFetchedAt,String publishedAt) {
        this(key,source,sourceId,title,company,city,location,url,salaryText,salaryMinK,salaryMaxK,salaryMonths,
            experienceText,educationText,description,fetchedAt,detailFetchedAt,publishedAt,JobType.UNKNOWN);
    }

    public JobRecord withDescription(String text,String time) {
        return new JobRecord(key,source,sourceId,title,company,city,location,url,salaryText,salaryMinK,salaryMaxK,salaryMonths,
            experienceText,educationText,text,fetchedAt,time,publishedAt,jobType);
    }

    public JobRecord withJobType(JobType value) {
        return new JobRecord(key,source,sourceId,title,company,city,location,url,salaryText,salaryMinK,salaryMaxK,salaryMonths,
            experienceText,educationText,description,fetchedAt,detailFetchedAt,publishedAt,value == null ? JobType.UNKNOWN : value);
    }
}
