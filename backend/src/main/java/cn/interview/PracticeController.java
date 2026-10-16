package cn.interview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v2")
class PracticeController {
    final PracticeService s;PracticeController(PracticeService s){this.s=s;}
    @PostMapping("/practice-cases") Object create(@RequestBody JsonNode b){return V2Responses.created("PRACTICE_CASE","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/practice-cases") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String sourceId){return s.caseList(cursor,limit,sourceId);}
    @GetMapping("/practice-cases/{id}") Object detail(@PathVariable String id){return s.visibleCase(s.db.get("v2_practice_cases",id));}
    @PostMapping("/practice-cases/{id}/reveal") Object reveal(@PathVariable String id,@RequestBody JsonNode b){return s.reveal(id,b);}
    @GetMapping("/practice-cases/{id}/attempts") Object attempts(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.attempts(id,cursor,limit);}
    @GetMapping("/practice-cases/{id}/recent-attempts") Object recent(@PathVariable String id){s.db.get("v2_practice_cases",id);var ids=s.db.legacy.jdbc.query("SELECT id FROM v2_practice_attempts WHERE case_id=? AND status IN ('IMPORTED','READY','FAILED','INTERRUPTED') ORDER BY ordinal DESC LIMIT 2",(rs,n)->rs.getString(1),id);var out=s.db.object();var items=out.putArray("items");for(int i=ids.size()-1;i>=0;i--)items.add(s.view(s.db.get("v2_practice_attempts",ids.get(i))));return out;}
    @PostMapping("/practice-cases/{id}/attempts") Object attempt(@PathVariable String id,@RequestBody JsonNode b){return V2Responses.created("PRACTICE_ATTEMPT",id,b,()->s.attempt(id,b));}
    @GetMapping("/practice-attempts/{id}") Object attempt(@PathVariable String id){return s.view(s.db.get("v2_practice_attempts",id));}
    @PutMapping("/practice-attempts/{id}/draft") Object draft(@PathVariable String id,@RequestBody JsonNode b){return s.draft(id,b);}
    @PostMapping("/practice-attempts/{id}/submit") Object submit(@PathVariable String id,@RequestBody JsonNode b){return s.submit(id,b);}
    @PostMapping("/practice-cases/{id}/evaluation-groups") Object group(@PathVariable String id,@RequestBody JsonNode b){return V2Responses.created("EVALUATION_GROUP",id,b,()->s.newGroup(id,b));}
    @GetMapping("/practice-cases/{id}/evaluation-groups") Object groups(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){var page=s.db.page("v2_practice_evaluation_groups",cursor,limit,null,"case_id",id);page.path("items").forEach(g->((com.fasterxml.jackson.databind.node.ObjectNode)g).remove(ListHolder.PRIVATE));return page;}
    @GetMapping("/practice-evaluation-groups/{id}") Object group(@PathVariable String id){return s.groupView(s.db.get("v2_practice_evaluation_groups",id));}
    @PostMapping("/practice-comparisons") Object compare(@RequestBody JsonNode b){return V2Responses.created(b.path("mode").asText().equals("COMPARE")?"PRACTICE_COMPARE":"PRACTICE_PAIR","LOCAL_USER",b,()->s.compare(b));}
    @GetMapping("/practice-comparisons/{id}") Object comparison(@PathVariable String id){return s.comparisonView(s.db.get("v2_practice_comparisons",id));}
    @GetMapping("/practice-comparisons") Object comparisons(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String caseId){var page=s.db.page("v2_practice_comparisons",cursor,limit,null,caseId==null?null:"case_id",caseId);page.path("items").forEach(g->((com.fasterxml.jackson.databind.node.ObjectNode)g).remove(ListHolder.PRIVATE));return page;}
    static class ListHolder{static final java.util.List<String> PRIVATE=java.util.List.of("protocol","result");}
}
