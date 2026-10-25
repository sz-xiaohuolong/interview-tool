package cn.interview.client.jobsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JobSourceParsingTest {
    @Test void monthlySalaryKeepsThirteenthMonthSeparateAndUnknownDoesNotBecomeZero(){
        assertThat(CdpJobSourceGateway.salary("20-30K·13薪")).isEqualTo(new CdpJobSourceGateway.Salary(20d,30d,13));
        assertThat(CdpJobSourceGateway.salary("2-3万元/月")).isEqualTo(new CdpJobSourceGateway.Salary(20d,30d,null));
        assertThat(CdpJobSourceGateway.salary("8000-12000元/月")).isEqualTo(new CdpJobSourceGateway.Salary(8d,12d,null));
        assertThat(CdpJobSourceGateway.salary("20K以上")).isEqualTo(new CdpJobSourceGateway.Salary(20d,null,null));
        assertThat(CdpJobSourceGateway.salary("30K以下")).isEqualTo(new CdpJobSourceGateway.Salary(null,30d,null));
        for(String value:new String[]{"面议","30-50万/年","200-500元/天","未知","10000"})assertThat(CdpJobSourceGateway.salary(value).min()).as(value).isNull();
    }
    @Test void zhaopinVerifiedVisibleSalaryAndFullLocationArePreserved() throws Exception {
        var json=new ObjectMapper();var gateway=new CdpJobSourceGateway(json,new cn.interview.service.jobsearch.JobCityCatalog());
        var row=json.createObjectNode().put("name","合成 Java 岗位").put("number","CC000000000J10000000000").put("jobId",10000000000L).put("companyName","合成公司").put("workCity","深圳").put("cityDistrict","合成区").put("streetName","合成街道").put("tradingArea","合成商圈").put("salary60","1-1.5万").put("salaryReal","10001-15000").put("salaryCount","13").put("education","本科").put("workingExp","1-3年").put("publishTime","2026-08-20 11:39:41");
        var job=gateway.parseZhaopinJob(row,"深圳");assertThat(job.salaryText()).isEqualTo("1-1.5万");assertThat(job.salaryMinK()).isEqualTo(10d);assertThat(job.salaryMaxK()).isEqualTo(15d);assertThat(job.salaryMonths()).isEqualTo(13);assertThat(job.location()).isEqualTo("深圳·合成区·合成街道·合成商圈");assertThat(job.publishedAt()).isEqualTo("2026-08-20T03:39:41Z");assertThat(job.fetchedAt()).isNotEqualTo(job.publishedAt());
        row.remove("number");assertThat(gateway.parseZhaopinJob(row,"深圳")).isNull();
    }
    @Test void concreteUrlsAreRestrictedAndSecretsAreRemoved(){
        assertThat(CdpJobSourceGateway.cleanUrl("BOSS","https://www.zhipin.com/job_detail/abc_123.html?securityId=private&lid=track#token")).isEqualTo("https://www.zhipin.com/job_detail/abc_123.html");
        for(String value:new String[]{"https://www.zhipin.com/","http://www.zhipin.com/job_detail/id.html","https://evil.example/job_detail/id.html","https://user:secret@www.zhipin.com/job_detail/id.html","https://www.zhipin.com:8443/job_detail/id.html","https://www.zhipin.com/job_detail/%0a.html"})assertThat(CdpJobSourceGateway.cleanUrl("BOSS",value)).as(value).isNull();
    }
    @Test void businessIdentityMustMatchConcreteUrlAndEncodedSalaryIsUnknown(){
        var n=new ObjectMapper().createObjectNode().put("sourceId","real-id").put("title","合成岗位").put("url","https://www.zhipin.com/job_detail/real-id.html").put("salaryText","\ue001-\ue002K").put("educationText"," 不限 ");
        var job=CdpJobSourceGateway.parseJob("BOSS",n,"深圳");assertThat(job.key()).isEqualTo("BOSS:real-id");assertThat(job.salaryText()).isEmpty();assertThat(job.salaryMinK()).isNull();assertThat(job.educationText()).isEqualTo("不限");
        n.put("sourceId","made-from-title");assertThat(CdpJobSourceGateway.parseJob("BOSS",n,"深圳")).isNull();
    }

    @Test void bossTypeCodesAreExpandedBeforeClassification(){
        var json=new ObjectMapper();
        assertThat(CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",4))).contains("实习");
        assertThat(CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",0))).contains("全职");
        assertThat(CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",5))).contains("校招");
        assertThat(CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",6))).contains("兼职");
    }

    @Test void concreteBossTypeCodeIsReflectedInTheUnifiedJobType(){
        var json=new ObjectMapper();
        var internship=json.createObjectNode().put("sourceId","intern").put("title","Java 工程师").put("url","https://www.zhipin.com/job_detail/intern.html").put("jobTypeText",CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",4)));
        var fullTime=json.createObjectNode().put("sourceId","full").put("title","Java 工程师").put("url","https://www.zhipin.com/job_detail/full.html").put("jobTypeText",CdpJobSourceGateway.bossTypeText(json.createObjectNode().put("jobType",0)));
        assertThat(CdpJobSourceGateway.parseJob("BOSS",internship,"深圳").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.DAILY_INTERNSHIP);
        assertThat(CdpJobSourceGateway.parseJob("BOSS",fullTime,"深圳").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.FULL_TIME);
    }

    @Test void zhaopinWorkTypeIsUsedWhenJobTypeAliasIsMissing(){
        var json=new ObjectMapper();var gateway=new CdpJobSourceGateway(json,new cn.interview.service.jobsearch.JobCityCatalog());
        var row=json.createObjectNode().put("name","Java 工程师").put("number","CC000000000J10000000001").put("positionURL","https://www.zhaopin.com/jobdetail/CC000000000J10000000001.htm").put("companyName","合成公司").put("workCity","深圳").put("workType","全职");
        assertThat(gateway.parseZhaopinJob(row,"深圳").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.FULL_TIME);
    }

    @Test void zhaopinNestedTypeAndStructuredLabelsAreReadWithoutTreatingCompanyNatureAsEmployment() throws Exception {
        var json=new ObjectMapper();var gateway=new CdpJobSourceGateway(json,new cn.interview.service.jobsearch.JobCityCatalog());
        var row=json.readTree("""
            {"name":"Java 工程师","positionURL":"https://www.zhaopin.com/jobdetail/CC000000000J10000000002.htm",
             "workCity":"青岛","jobDetailData":{"position":{"base":{"workType":"全职"}}}}
            """);
        assertThat(gateway.parseZhaopinJob(row,"所有").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.FULL_TIME);
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).remove("jobDetailData");
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("propertyName","全职教育机构");
        assertThat(gateway.parseZhaopinJob(row,"所有").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.UNKNOWN);
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).set("workType",json.readTree("{\"name\":\"实习\"}"));
        assertThat(gateway.parseZhaopinJob(row,"所有").jobType()).isEqualTo(cn.interview.entity.jobsearch.JobType.DAILY_INTERNSHIP);
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).remove("workType");
        for(String alias:java.util.List.of("jobType","positionType")){
            ((com.fasterxml.jackson.databind.node.ObjectNode)row).put(alias,"全职");
            assertThat(gateway.parseZhaopinJob(row,"所有").jobType()).as(alias).isEqualTo(cn.interview.entity.jobsearch.JobType.FULL_TIME);
            ((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(alias);
        }
    }
}
