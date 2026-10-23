package cn.interview.client.jobsearch;

import cn.interview.entity.jobsearch.JobRecord;
import java.util.List;

/** 每个搜索来源独享一个可取消游标，浏览器资源不进入接口响应。 */
public interface JobSourceGateway {
    Cursor open(String source,String keyword,String cityId);
    interface Cursor extends AutoCloseable {
        Batch next();
        JobRecord detail(JobRecord job);
        void close();
    }
    record Batch(List<JobRecord> jobs,boolean hasMore) {}
}
