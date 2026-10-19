package cn.interview.controller.training;

import cn.interview.common.ApiResponses;
import cn.interview.service.training.PracticeService;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({"/api/training", "/api/v2"})
class PracticeController {
    final PracticeService s;PracticeController(PracticeService s){this.s=s;}
    @PostMapping("/practice-cases") Object create(@RequestBody JsonNode b){return ApiResponses.created("PRACTICE_CASE","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/practice-cases") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String sourceId){return s.caseList(cursor,limit,sourceId);}
    @GetMapping("/practice-cases/{id}") Object detail(@PathVariable String id){return s.getCase(id);}
    @PostMapping("/practice-cases/{id}/reveal") Object reveal(@PathVariable String id,@RequestBody JsonNode b){return s.reveal(id,b);}
    @GetMapping("/practice-cases/{id}/attempts") Object attempts(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.attempts(id,cursor,limit);}
    @GetMapping("/practice-cases/{id}/recent-attempts") Object recent(@PathVariable String id){return s.recentAttempts(id);}
    @PostMapping("/practice-cases/{id}/attempts") Object attempt(@PathVariable String id,@RequestBody JsonNode b){return ApiResponses.created("PRACTICE_ATTEMPT",id,b,()->s.attempt(id,b));}
    @GetMapping("/practice-attempts/{id}") Object attempt(@PathVariable String id){return s.getAttempt(id);}
    @PutMapping("/practice-attempts/{id}/draft") Object draft(@PathVariable String id,@RequestBody JsonNode b){return s.draft(id,b);}
    @PostMapping("/practice-attempts/{id}/submit") Object submit(@PathVariable String id,@RequestBody JsonNode b){return s.submit(id,b);}
    @PostMapping("/practice-cases/{id}/evaluation-groups") Object group(@PathVariable String id,@RequestBody JsonNode b){return ApiResponses.created("EVALUATION_GROUP",id,b,()->s.newGroup(id,b));}
    @GetMapping("/practice-cases/{id}/evaluation-groups") Object groups(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.groups(id,cursor,limit);}
    @GetMapping("/practice-evaluation-groups/{id}") Object group(@PathVariable String id){return s.getGroup(id);}
    @PostMapping("/practice-comparisons") Object compare(@RequestBody JsonNode b){return ApiResponses.created(b.path("mode").asText().equals("COMPARE")?"PRACTICE_COMPARE":"PRACTICE_PAIR","LOCAL_USER",b,()->s.compare(b));}
    @GetMapping("/practice-comparisons/{id}") Object comparison(@PathVariable String id){return s.getComparison(id);}
    @GetMapping("/practice-comparisons") Object comparisons(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String caseId){return s.comparisons(cursor,limit,caseId);}
}
