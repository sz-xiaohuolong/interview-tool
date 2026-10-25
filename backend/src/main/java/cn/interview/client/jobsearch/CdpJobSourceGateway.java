package cn.interview.client.jobsearch;

import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import cn.interview.service.jobsearch.JobCityCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 复用 Chrome 正常会话读取岗位；不导出 Cookie、不访问调试 HTTP 端点。 */
@Component
public final class CdpJobSourceGateway implements JobSourceGateway {
    private static final Semaphore FOREGROUND=new Semaphore(1,true);
    private final ObjectMapper json;
    private final JobCityCatalog cities;
    private final Path configured;
    private volatile CdpBrowser sharedBrowser;
    private volatile boolean closed;
    @Autowired public CdpJobSourceGateway(ObjectMapper json,JobCityCatalog cities){this(json,cities,null);}
    CdpJobSourceGateway(ObjectMapper json,JobCityCatalog cities,Path configured){this.json=json;this.cities=cities;this.configured=configured;}
    @Override public synchronized Cursor open(String source,String keyword,String cityId){
        if(closed)throw CdpBrowser.failure("SOURCE_NETWORK");
        String code=cities.sourceCode(cityId,source);
        return new CdpCursor(source,keyword,cityId,code,browser(),false);
    }
    private synchronized CdpBrowser browser(){
        if(sharedBrowser==null)sharedBrowser=new CdpBrowser(activePortFile(),json);
        return sharedBrowser;
    }
    @PreDestroy public synchronized void close(){closed=true;if(sharedBrowser!=null){sharedBrowser.close();sharedBrowser=null;}}
    private Path activePortFile(){
        if(configured!=null)return configured;
        String explicit=System.getenv("INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE");if(explicit!=null&&!explicit.isBlank())return Path.of(explicit).toAbsolutePath().normalize();
        String home=System.getProperty("user.home");var candidates=List.of(Path.of(home,"Library/Application Support/Google/Chrome/DevToolsActivePort"),Path.of(home,"Library/Application Support/Chromium/DevToolsActivePort"),Path.of(home,".config/google-chrome/DevToolsActivePort"),Path.of(home,".config/chromium/DevToolsActivePort"));return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(candidates.getFirst());
    }
    private final class CdpCursor implements Cursor {
        private final String source,keyword,cityId,cityCode;
        private final CdpBrowser browser;
        private final boolean ownsBrowser;
        private final CdpBrowser.Page page;
        private final BlockingQueue<Response> responses=new ArrayBlockingQueue<>(4);
        private final Map<String,Response> loading=new ConcurrentHashMap<>();
        private final AtomicBoolean permit=new AtomicBoolean(true);
        private final LinkedHashSet<String> seen=new LinkedHashSet<>();
        // 源站列表提供的临时详情链接只留在游标内存，不进入岗位、收藏、日志或资料元数据。
        private final Map<String,String> bossDetailLinks=new ConcurrentHashMap<>();
        private volatile boolean closed;
        private volatile int allowedPage=1;
        private volatile String bossRequestUrl;
        private int pageNumber;
        private boolean attempted;
        CdpCursor(String source,String keyword,String cityId,String cityCode,CdpBrowser browser,boolean ownsBrowser){
            this.source=source;this.keyword=keyword;this.cityId=cityId;this.cityCode=cityCode;this.browser=browser;this.ownsBrowser=ownsBrowser;
            CdpBrowser.Page created=null;
            try{
                created=browser.page();this.page=created;page.events(this::event);page.command("Network.enable",Map.of("maxTotalBufferSize",4_000_000,"maxResourceBufferSize",2_000_000));
                if(source.equals("BOSS"))page.command("Fetch.enable",Map.of("patterns",List.of(Map.of("urlPattern","*://www.zhipin.com/wapi/zpgeek/search/joblist.json*","requestStage","Request"))));
                page.navigate(searchUrl(0));
            }catch(RuntimeException e){if(created!=null)created.close();if(ownsBrowser)browser.close();throw e;}
        }
        private String searchUrl(int number){return switch(source){
            case "BOSS"->"https://www.zhipin.com/web/geek/jobs?query="+enc(keyword)+"&city="+cityCode;
            case "LIEPIN"->"https://www.liepin.com/zhaopin/?key="+enc(keyword)+"&dq="+cityCode+"&currentPage="+number;
            case "ZHAOPIN"->"https://www.zhaopin.com/jobs/?jl="+cityCode+"&kw="+enc(keyword);
            default->throw ApiException.bad("不支持的招聘来源");};}
        private void event(JsonNode event){
            if(closed)return;String method=event.path("method").asText();JsonNode p=event.path("params");
            if(method.equals("Fetch.requestPaused")){
                String url=p.path("request").path("url").asText();boolean allow=isListUrl(source,url)&&queryPage(url)==allowedPage&&permit.compareAndSet(true,false);
                page.fire(allow?"Fetch.continueRequest":"Fetch.failRequest",allow?Map.of("requestId",p.path("requestId").asText()):Map.of("requestId",p.path("requestId").asText(),"errorReason","Aborted"));return;
            }
            if(method.equals("Network.responseReceived")){
                JsonNode response=p.path("response");String url=response.path("url").asText();int status=response.path("status").asInt();
                // 智联滚动加载会先发一个 204 预检请求，不能把它当作列表正文读取；只等待实际有正文的 2xx 响应。
                if(isListUrl(source,url)&&status>=200&&status<300&&status!=204&&loading.size()<4){if(source.equals("BOSS"))bossRequestUrl=url;loading.put(p.path("requestId").asText(),new Response(p.path("requestId").asText(),status));}
            }else if(method.equals("Network.loadingFinished")){Response r=loading.remove(p.path("requestId").asText());if(r!=null)responses.offer(r);}
            else if(method.equals("Network.loadingFailed"))loading.remove(p.path("requestId").asText());
        }
        @Override public synchronized Batch next(){
            ensureOpen();if(attempted&&pageNumber==0){permit.set(true);responses.clear();page.navigate(searchUrl(0));}attempted=true;JsonNode data;
            if(source.equals("BOSS")){
                if(pageNumber>0){if(bossRequestUrl==null)throw CdpBrowser.failure("SOURCE_CHANGED");allowedPage=pageNumber+1;permit.set(true);responses.clear();
                    String next=replacePage(bossRequestUrl,allowedPage);
                    // 请求仍在已登录的同源页面中完成，认证由 Chrome 自己处理。
                    try{page.evaluate("(async()=>{const r=await fetch("+quoted(next)+",{credentials:'same-origin'});await r.text();return true})()");}catch(ApiException e){if(responses.isEmpty())throw e;}
                }
                data=networkBody();JsonNode payload=data.has("zpData")?data.path("zpData"):data.path("data");JsonNode rows=payload.path("jobList");
                if(!rows.isArray()){checkRemoteError(data);throw CdpBrowser.failure("SOURCE_CHANGED");}
                var jobs=parseBoss(rows);pageNumber++;return remember(jobs,payload.path("hasMore").asBoolean(false));
            }
            if(source.equals("LIEPIN")){
                if(pageNumber>0)page.navigate(searchUrl(pageNumber));
                data=waitForList(liepinExpression());pageNumber++;var jobs=new ArrayList<JobRecord>();for(JsonNode item:data.path("jobs")){JobRecord j=parseJob(source,item,cities.name(cityId));if(j!=null)jobs.add(j);}
                return remember(jobs,data.path("hasMore").asBoolean(false));
            }
            if(pageNumber==0)data=waitForList(zhaopinExpression());
            else{
                responses.clear();foreground(()->{page.foreground();page.evaluate("(()=>{window.scrollTo(0,document.documentElement.scrollHeight);const c=document.querySelector('.job-list,.job-list-box,.position-list');if(c)c.scrollTop=c.scrollHeight;return true})()");});
                // 智联的下一批由正常滚动触发；从返回中读取真实职位身份。
                JsonNode body=networkBody();JsonNode rows=zhaopinRows(body);if(!rows.isArray())throw CdpBrowser.failure("SOURCE_CHANGED");data=json.createObjectNode().set("jobs",rows);
                ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("hasMore",!rows.isEmpty());
            }
            var jobs=parseZhaopin(data.path("jobs"),cities.name(cityId));pageNumber++;return remember(jobs,data.path("hasMore").asBoolean(false));
        }
        private Batch remember(List<JobRecord> jobs,boolean more){
            var fresh=new ArrayList<JobRecord>();for(JobRecord j:jobs)if(seen.size()<100&&seen.add(j.key()))fresh.add(j);
            if(fresh.isEmpty()&&!jobs.isEmpty()&&more)throw CdpBrowser.failure("SOURCE_CHANGED");
            return new Batch(List.copyOf(fresh),more&&seen.size()<100);
        }
        private List<JobRecord> parseBoss(JsonNode rows){
            var jobs=new ArrayList<JobRecord>();for(JsonNode r:rows){String id=text(r,"encryptJobId");if(id.isEmpty())continue;var n=json.createObjectNode().put("sourceId",id).put("url","https://www.zhipin.com/job_detail/"+id+".html").put("title",text(r,"jobName")).put("company",text(r,"brandName")).put("city",text(r,"cityName")).put("location",String.join("·",List.of(text(r,"cityName"),text(r,"areaDistrict"),text(r,"businessDistrict")).stream().filter(s->!s.isEmpty()).toList())).put("salaryText",text(r,"salaryDesc")).put("experienceText",text(r,"jobExperience")).put("educationText",text(r,"jobDegree")).put("jobTypeText",bossTypeText(r));JobRecord job=parseJob(source,n,cities.name(cityId));if(job!=null){jobs.add(job);String access=text(r,"securityId");if(!access.isBlank()&&access.length()<=8192&&bossDetailLinks.size()<100)bossDetailLinks.put(job.key(),job.url()+"?securityId="+enc(access));}}if(!rows.isEmpty()&&jobs.isEmpty())throw CdpBrowser.failure("SOURCE_CHANGED");return jobs;
        }
        private JsonNode networkBody(){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
            try{while(System.nanoTime()<end){ensureOpen();Response r=responses.poll(200,TimeUnit.MILLISECONDS);if(r==null)continue;if(r.status()==429)throw new ApiException(429,"RATE_LIMITED","访问过于频繁");if(r.status()==401||r.status()==403)throw new ApiException(503,"SESSION_EXPIRED","来源会话不可用");if(r.status()>=400)throw CdpBrowser.failure("SOURCE_NETWORK");JsonNode result=page.command("Network.getResponseBody",Map.of("requestId",r.id()));String body=result.path("body").asText();if(result.path("base64Encoded").asBoolean())body=new String(Base64.getDecoder().decode(body),StandardCharsets.UTF_8);if(body.length()>2_000_000)throw CdpBrowser.failure("SOURCE_CHANGED");return json.readTree(body);}
                checkPage(page.evaluate(pageStatus()));throw CdpBrowser.failure("SOURCE_TIMEOUT");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw CdpBrowser.failure("SOURCE_TIMEOUT");}catch(ApiException e){throw e;}catch(Exception e){throw CdpBrowser.failure("SOURCE_CHANGED");}
        }
        private JsonNode waitForList(String expression){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);long fallbackAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){ensureOpen();JsonNode result=page.evaluate(expression);if(result.path("jobs").isArray()&&!result.path("jobs").isEmpty()){
                    // 智联首屏的 DOM 卡片可能先于内嵌职位状态出现；优先等到带岗位性质的状态数据，避免把全职岗位误报为未知。
                    if(result.path("embedded").asBoolean()||System.nanoTime()>=fallbackAt||!source.equals("ZHAOPIN"))return result;
                }
                checkPage(result);if(result.path("emptyKnown").asBoolean())return result;pause();}throw CdpBrowser.failure("SOURCE_CHANGED");
        }
        @Override public synchronized JobRecord detail(JobRecord job){
            ensureOpen();if(cleanUrl(source,job.url())==null)throw ApiException.bad("岗位链接不属于对应招聘来源");
            try(CdpBrowser.Page detail=browser.page()){
                detail.navigate(source.equals("BOSS")?bossDetailLinks.getOrDefault(job.key(),job.url()):job.url());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
                String selector=switch(source){case "BOSS"->".job-detail .job-detail-section .job-sec-text, .job-detail-body .desc";case "LIEPIN"->"[data-selector=job-intro-content]";default->".describtion-card__detail-content";};
                while(System.nanoTime()<end){ensureOpen();JsonNode result=detail.evaluate("(()=>{const e=document.querySelector("+quoted(selector)+");const body=document.body?.innerText||'';return {description:(e?.innerText||e?.textContent||'').trim(),url:location.href,title:document.title,text:body.slice(0,2500)+body.slice(-2500)}})()");checkPage(result);String description=text(result,"description"),actual=cleanUrl(source,text(result,"url"));if(!description.isEmpty()&&actual!=null&&identity(actual).equals(job.sourceId())){if(description.length()>100_000)throw CdpBrowser.failure("SOURCE_CHANGED");JobRecord detailed=job.withDescription(description,Instant.now().toString());if(job.jobType()==cn.interview.entity.jobsearch.JobType.UNKNOWN){var inferred=JobTypeClassifier.classify(job.title(),"",description);if(inferred!=cn.interview.entity.jobsearch.JobType.UNKNOWN)detailed=detailed.withJobType(inferred);}return detailed;}pause();}
                throw CdpBrowser.failure("SOURCE_CHANGED");
            }
        }
        private void ensureOpen(){if(closed||Thread.currentThread().isInterrupted())throw CdpBrowser.failure("SOURCE_TIMEOUT");}
        @Override public void close(){if(closed)return;closed=true;loading.clear();responses.clear();bossDetailLinks.clear();try{page.close();}catch(RuntimeException ignored){}if(ownsBrowser)try{browser.close();}catch(RuntimeException ignored){}}
    }
    private record Response(String id,int status) {}
    private static boolean isListUrl(String source,String url){try{URI u=URI.create(url);return "https".equals(u.getScheme())&&u.getUserInfo()==null&&switch(source){case "BOSS"->"www.zhipin.com".equals(u.getHost())&&"/wapi/zpgeek/search/joblist.json".equals(u.getPath());case "ZHAOPIN"->Set.of("www.zhaopin.com","fe-api.zhaopin.com").contains(u.getHost())&&"/c/i/search/positions".equals(u.getPath());default->false;};}catch(Exception e){return false;}}
    private static int queryPage(String url){Matcher m=Pattern.compile("(?:[?&])page=(\\d+)").matcher(url);return m.find()?Integer.parseInt(m.group(1)):1;}
    private static String replacePage(String url,int page){return url.matches(".*[?&]page=\\d+.*")?url.replaceFirst("([?&]page=)\\d+","$1"+page):url+(url.contains("?")?"&":"?")+"page="+page;}
    private static void pause(){try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw CdpBrowser.failure("SOURCE_TIMEOUT");}}
    private static void foreground(Runnable action){boolean held=false;try{held=FOREGROUND.tryAcquire(8,TimeUnit.SECONDS);if(!held)throw CdpBrowser.failure("SOURCE_TIMEOUT");action.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw CdpBrowser.failure("SOURCE_TIMEOUT");}finally{if(held)FOREGROUND.release();}}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private String quoted(String value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("文本编码失败");}}
    private static String text(JsonNode n,String field){return n.path(field).asText("").strip();}
    private static String optional(JsonNode n,String field){String value=text(n,field);return value.isEmpty()?null:value;}
    private static void checkRemoteError(JsonNode n){String message=text(n,"message")+text(n,"msg");if(n.path("code").asInt()==0&&n.has("zpData"))return;checkPage(new ObjectMapper().createObjectNode().put("text",message).put("url",""));}
    private static void checkPage(JsonNode n){String text=n.path("text").asText("");String url=n.path("url").asText("");String title=n.path("title").asText("");if(title.contains("安全验证")||url.matches(".*(?:/web/user/safe|/captcha|/verify|/security-check).*" )||text.matches("(?s).*(?:请完成安全验证|请进行安全验证|请拖动滑块|人机验证|验证码验证|^安全验证$).*"))throw new ApiException(503,"VERIFICATION_REQUIRED","来源需要安全验证");if(url.matches(".*(?:/login|/passport).*" )||text.matches("(?s).*(?:请先登录后|登录后查看|登录后继续|登录已过期|登录查看完整内容|登录查看全文|登录查看更多).*"))throw new ApiException(503,"SESSION_EXPIRED","来源需要恢复登录");if(text.matches("(?s).*(?:访问过于频繁|请求过于频繁|操作频繁，请稍后).*"))throw new ApiException(429,"RATE_LIMITED","来源暂时限流");}
    private static String pageStatus(){return "({url:location.href,title:document.title,text:(document.body?.innerText||'').slice(0,2500)})";}
    private static String liepinExpression(){return """
        (()=>{const t=e=>(e?.innerText||e?.textContent||'').trim();const cards=[...document.querySelectorAll('.jobCardPcContainer')];const jobs=[];for(const c of cards){if(!c.getClientRects().length)continue;const a=c.querySelector('.jobDetailBox a[href*="/job/"],a[href*="/job/"],a[href*="/a/"]');if(!a)continue;const u=new URL(a.href,location.origin);const id=(u.pathname.match(/\\/(?:job|a)\\/([^/]+)\\.shtml$/)||[])[1];if(!id)continue;const lines=t(c).split(/\\n+/).map(x=>x.trim()).filter(Boolean);if(lines.includes('广告'))continue;const title=t(c.querySelector('.job-title,.job-name,h3'))||lines[0]||'';const salary=t(c.querySelector('.job-salary,.salary'))||lines.find(x=>/\\d.*(?:[kK]|万|元|薪)/.test(x))||'';const place=t(c.querySelector('.job-dq,.job-location,.job-area,.jobTitleBox > div:nth-child(2) .ellipsis-1'))||(lines.includes('【')?lines[lines.indexOf('【')+1]:'')||'';jobs.push({sourceId:id,url:u.href,title,company:t(c.querySelector('.company-name,.company-name-text,[class*=companyName]')),city:place.replace(/[【】]/g,'').trim().split(/[-·\\s]/)[0]||'',location:place,salaryText:salary,experienceText:lines.find(x=>/经验|\\d+.*年|应届/.test(x))||'',educationText:lines.find(x=>/本科|大专|硕士|博士|学历|学历不限/.test(x))||'',jobTypeText:lines.find(x=>/日常实习|暑期实习|实习生|实习|全职|正式岗位|full[- ]?time/i.test(x))||''});}const body=t(document.body);return {jobs,hasMore:!!document.querySelector('.ant-pagination-next:not(.ant-pagination-disabled)'),emptyKnown:/没有找到|暂无职位|暂无相关职位/.test(body),url:location.href,title:document.title,text:body.slice(0,2500)}})()
        """;}
    // 页面初始化后全局状态可能已被清理；仅解析内嵌 JSON，不执行页面脚本。
    private static String zhaopinExpression(){return """
        (()=>{const rows=list=>Array.isArray(list)?list:(list?.list||list?.data?.list||[]);const typed=list=>list.some(r=>['workType','workTypeName','jobType','jobTypeName','positionType','positionTypeName','employmentType','jobNature','jobNatureName','jobLabels','labels','jobTags'].some(k=>r?.[k]!=null)||r?.jobDetailData?.position?.base?.workType!=null);let jobs=rows(window.__INITIAL_STATE__?.positionList),embedded=jobs.length>0&&typed(jobs);for(const script of document.scripts){const raw=script.textContent||'';if(raw.length>2000000)continue;const marker=raw.indexOf('__INITIAL_STATE__');if(marker<0)continue;const start=raw.indexOf('{',marker);const end=raw.lastIndexOf('}');if(start<0||end<=start)continue;try{const candidate=rows(JSON.parse(raw.slice(start,end+1)).positionList);if(candidate.length&&(!jobs.length||(!embedded&&typed(candidate)))){jobs=candidate;embedded=typed(candidate);if(embedded)break;}}catch{}}if(!jobs.length){for(const c of document.querySelectorAll('.job-card')){const a=c.querySelector('a[href*="/jobdetail/"]');if(a)jobs.push({positionURL:a.href,name:(c.querySelector('.job-card__title-clamp')?.innerText||a.innerText||'').trim(),salary:(c.querySelector('.job-card__salary')?.innerText||'').trim(),companyName:(c.querySelector('.job-card__company-name')?.innerText||'').trim(),workCity:(c.querySelector('.job-card__location')?.innerText||'').trim()});}}const text=(document.body?.innerText||'').slice(0,2500);return {jobs,embedded,hasMore:jobs.length>0,emptyKnown:/没有找到|暂无职位|暂无相关职位/.test(text),url:location.href,title:document.title,text}})()
        """;}
    static JsonNode zhaopinRows(JsonNode body){for(JsonNode n:List.of(body.path("data").path("list"),body.path("data").path("positionList"),body.path("positionList")))if(n.isArray())return n;return body.path("data").path("list");}
    private List<JobRecord> parseZhaopin(JsonNode rows,String city){var jobs=new ArrayList<JobRecord>();for(JsonNode r:rows){JobRecord job=parseZhaopinJob(r,city);if(job!=null)jobs.add(job);}if(!rows.isEmpty()&&jobs.isEmpty())throw CdpBrowser.failure("SOURCE_CHANGED");return jobs;}
    JobRecord parseZhaopinJob(JsonNode row,String city){
        // 优先保留真实具体职位链接；缺少身份或链接时拒绝，绝不以岗位名称造 ID。
        String url=text(row,"positionURL");if(url.isEmpty())url=text(row,"positionUrl");if(url.isEmpty())url=text(row,"jobUrl");
        String clean=cleanUrl("ZHAOPIN",url);
        if(clean==null){String number=first(row,"number","jobNumber");if(number.matches("CC[0-9]+J[0-9]+"))clean="https://www.zhaopin.com/jobdetail/"+number+".htm";}
        if(clean==null)return null;String id=identity(clean);
        var parts=new LinkedHashSet<String>();for(String field:List.of("workCity","cityDistrict","streetName","tradingArea")){String part=fieldText(row.path(field));if(!part.isEmpty())parts.add(part);}
        if(parts.isEmpty()){String place=first(row,"workCityName","cityName");if(!place.isEmpty())parts.add(place);}
        String salaryText=fieldText(row.path("salary60"));if(salaryText.isEmpty())salaryText=first(row,"salary","salaryDesc");
        // propertyName／propertyType 是公司性质，不能拿来判断岗位是全职还是实习。
        String jobTypeText=typeText(row,"jobType","jobTypeName","positionType","positionTypeName","employmentType","jobNature","jobNatureName","jobLabels","labels","jobTags","workType","workTypeName")+" "+typeText(row.path("jobDetailData").path("position").path("base"),"workType");
        var n=json.createObjectNode().put("sourceId",id).put("url",clean).put("title",first(row,"name","jobName")).put("company",first(row,"companyName","company")).put("city",first(row,"workCity","cityName")).put("location",String.join("·",parts)).put("salaryText",salaryText).put("experienceText",first(row,"workingExp","workExperience")).put("educationText",first(row,"education","educationName")).put("jobTypeText",jobTypeText);
        String count=fieldText(row.path("salaryCount"));if(count.matches("[0-9]{1,2}")){int months=Integer.parseInt(count);if(months>0&&months<=36)n.put("salaryMonths",months);}
        String published=publishedAt(fieldText(row.path("publishTime")));if(published!=null)n.put("publishedAt",published);
        return parseJob("ZHAOPIN",n,city);
    }
    private static String publishedAt(String value){
        if(value.isBlank())return null;
        try{return java.time.Instant.parse(value).toString();}catch(java.time.format.DateTimeParseException ignored){}
        try{return java.time.OffsetDateTime.parse(value).toInstant().toString();}catch(java.time.format.DateTimeParseException ignored){}
        try{return java.time.LocalDateTime.parse(value,java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toString();}catch(java.time.format.DateTimeParseException ignored){return null;}
    }
    private static String first(JsonNode n,String... fields){for(String field:fields){String value=fieldText(n.path(field));if(!value.isEmpty())return value;}return "";}
    static String bossTypeText(JsonNode row){
        String visible=typeText(row,"jobTypeName","positionType","employmentType","jobNature","jobLabels");
        String raw=visibleText(row.path("jobType"));
        return switch(raw){case "0"->visible+" 全职";case "4"->visible+" 实习";case "5"->visible+" 校招";case "6"->visible+" 兼职";default->visible+" "+raw;};
    }
    /** 站点字段名称和形状经常变化；把岗位标签及对象/数组中的可见文本汇总给分类器。 */
    private static String typeText(JsonNode n,String... fields){
        var values=new ArrayList<String>();
        for(String field:fields){String value=visibleText(n.path(field));if(!value.isBlank())values.add(value);}
        return String.join(" ",values);
    }
    private static String visibleText(JsonNode value){
        if(value==null||value.isMissingNode()||value.isNull())return "";
        if(value.isTextual()||value.isNumber()||value.isBoolean())return value.asText().strip();
        if(value.isArray()){var values=new ArrayList<String>();for(JsonNode child:value){String text=visibleText(child);if(!text.isBlank())values.add(text);}return String.join(" ",values);}
        if(value.isObject()){
            var values=new ArrayList<String>();for(String field:List.of("name","label","text","value","title","desc","description")){String text=visibleText(value.path(field));if(!text.isBlank())values.add(text);}return String.join(" ",values);
        }
        return "";
    }
    private static String fieldText(JsonNode value){if(value.isTextual())return value.asText().strip();if(value.isObject()){String name=value.path("name").asText("");return name.isBlank()?value.path("cityName").asText("").strip():name.strip();}return "";}
    static JobRecord parseJob(String source,JsonNode n,String cityName){
        String url=cleanUrl(source,text(n,"url")),id=text(n,"sourceId"),title=text(n,"title");if(url==null||id.isEmpty()||title.isEmpty()||!id.equals(identity(url)))return null;
        String rawSalary=text(n,"salaryText");if(rawSalary.codePoints().anyMatch(c->Character.getType(c)==Character.PRIVATE_USE))rawSalary="";Salary salary=salary(rawSalary);
        var type=JobTypeClassifier.classify(title,text(n,"jobTypeText"),optional(n,"description"));
        return new JobRecord(source+":"+id,source,id,title,text(n,"company"),text(n,"city").isEmpty()?(text(n,"location").contains(cityName)?cityName:""):text(n,"city"),text(n,"location"),url,rawSalary,salary.min(),salary.max(),salary.months()!=null?salary.months():n.path("salaryMonths").canConvertToInt()?n.path("salaryMonths").asInt():null,optional(n,"experienceText"),optional(n,"educationText"),null,Instant.now().toString(),null,optional(n,"publishedAt"),type);
    }
    static String identity(String url){String path=URI.create(url).getPath();String last=path.substring(path.lastIndexOf('/')+1);return last.replaceFirst("\\.(?:html|shtml|htm)$","");}
    static String cleanUrl(String source,String raw){
        try{URI u=URI.create(raw);if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return null;
            String host=u.getHost(),path=u.getPath();boolean valid=switch(source){case "BOSS"->"www.zhipin.com".equals(host)&&path.matches("/job_detail/[A-Za-z0-9_-]+\\.html");case "LIEPIN"->"www.liepin.com".equals(host)&&path.matches("/(?:job|a)/[A-Za-z0-9_-]+\\.shtml");case "ZHAOPIN"->"www.zhaopin.com".equals(host)&&path.matches("/jobdetail/[A-Za-z0-9_-]+\\.htm[l]?");default->false;};
            return valid?new URI("https",null,host,-1,path,null,null).toString():null;
        }catch(Exception e){return null;}
    }
    record Salary(Double min,Double max,Integer months) {}
    static Salary salary(String raw){
        String s=raw.replace(",","").replace("，","").strip();Matcher months=Pattern.compile("(?:[·•\\s]|^)(\\d{1,2})薪").matcher(s);Integer count=months.find()?Integer.valueOf(months.group(1)):null;
        if(s.matches("(?s).*(?:面议|年薪|/年|／年|元/天|元／天|元/时|元／时|日薪|时薪).*"))return new Salary(null,null,count);
        Matcher range=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~—至到]\\s*(\\d+(?:\\.\\d+)?)\\s*([kK千万元])").matcher(s);
        if(range.find()){double scale=unit(range.group(3)),min=Double.parseDouble(range.group(1))*scale,max=Double.parseDouble(range.group(2))*scale;return min<=max?new Salary(min,max,count):new Salary(null,null,count);}
        Matcher one=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([kK千万元])").matcher(s);if(one.find()){double value=Double.parseDouble(one.group(1))*unit(one.group(2));return s.contains("以上")||s.contains("起")?new Salary(value,null,count):s.contains("以下")?new Salary(null,value,count):new Salary(value,value,count);}
        return new Salary(null,null,count);
    }
    private static double unit(String unit){return switch(unit){case "万"->10;case "元"->0.001;default->1;};}
}
