package cn.interview.client.jobsearch;

import cn.interview.entity.jobsearch.JobType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobTypeClassifierTest {
    @Test
    void prioritizesSummerInternshipBeforeGeneralInternship() {
        assertEquals(JobType.SUMMER_INTERNSHIP, JobTypeClassifier.classify("Java 暑期实习", "实习", ""));
    }

    @Test
    void classifiesDailyInternshipAndFullTimeFromExplicitText() {
        assertEquals(JobType.DAILY_INTERNSHIP, JobTypeClassifier.classify("Java 实习生", "", "每周到岗三天"));
        assertEquals(JobType.FULL_TIME, JobTypeClassifier.classify("Java 工程师", "全职", ""));
    }

    @Test
    void keepsAmbiguousTextUnknown() {
        assertEquals(JobType.UNKNOWN, JobTypeClassifier.classify("Java 工程师", "校招", "应届生优先"));
    }
}
