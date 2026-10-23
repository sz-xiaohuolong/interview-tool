package cn.interview.controller.jobsearch;

import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.service.jobsearch.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/job-search")
public class JobSearchController {
    private final JobSearchService search;private final JobSearchLibraryService library;private final JobCityCatalog cities;
    public JobSearchController(JobSearchService search,JobSearchLibraryService library,JobCityCatalog cities){this.search=search;this.library=library;this.cities=cities;}
    @GetMapping("/cities") public List<Map<String,String>> cities(){return cities.cities();}
    @PostMapping("/tasks") public JobSearchService.TaskSnapshot create(@RequestBody JsonNode body){return search.create(body);}
    @GetMapping("/tasks/{id}") public JobSearchService.TaskSnapshot get(@PathVariable String id){return search.get(id);}
    @PostMapping("/tasks/{id}/more") public JobSearchService.TaskSnapshot more(@PathVariable String id){return search.more(id);}
    @PostMapping("/tasks/{id}/retry") public JobSearchService.TaskSnapshot retry(@PathVariable String id,@RequestBody JsonNode body){return search.retry(id,body);}
    @PostMapping("/tasks/{id}/stop") public JobSearchService.TaskSnapshot stop(@PathVariable String id){return search.stop(id);}
    @PostMapping("/tasks/{id}/jobs/{key}/detail") public JobRecord detail(@PathVariable String id,@PathVariable String key){return search.detail(id,key);}
    @GetMapping("/favorites") public List<ObjectNode> favorites(){return library.favorites();}
    @PostMapping("/favorites") public ObjectNode favorite(@RequestBody JsonNode body){return library.favorite(body);}
    @DeleteMapping("/favorites/{id}") public ResponseEntity<Void> delete(@PathVariable String id){library.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/jd-links/lookup") public ObjectNode lookup(@RequestBody JsonNode body){return library.lookup(body);}
    @PostMapping("/jd") public ObjectNode jd(@RequestBody JsonNode body){return library.saveJd(body);}
}
