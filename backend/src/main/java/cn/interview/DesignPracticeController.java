package cn.interview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v2")
class DesignPracticeController {
    final DesignPracticeService s;DesignPracticeController(DesignPracticeService s){this.s=s;}
    @GetMapping("/design-scenarios") Object scenarios(){s.db.enabled();return s.scenarios();}
    @PostMapping("/design-sessions") Object create(@RequestBody JsonNode b){return V2Responses.created("DESIGN_SESSION","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/design-sessions") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){var page=s.db.page("v2_design_sessions",cursor,limit,null);for(JsonNode row:page.path("items"))((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(java.util.List.of("constraints","stages","summary","scenario","earlyReason"));return page;}
    @GetMapping("/design-sessions/{id}") Object get(@PathVariable String id){return s.view(s.db.get("v2_design_sessions",id));}
    @PutMapping("/design-sessions/{id}/draft") Object draft(@PathVariable String id,@RequestBody JsonNode b){return s.draft(id,b);}
    @PostMapping("/design-sessions/{id}/submit") Object submit(@PathVariable String id,@RequestBody JsonNode b){return s.submit(id,b);}
    @PostMapping("/design-sessions/{id}/advance") Object advance(@PathVariable String id,@RequestBody JsonNode b){return s.advance(id,b);}
    @PostMapping("/design-sessions/{id}/reopen") Object reopen(@PathVariable String id,@RequestBody JsonNode b){return s.reopen(id,b);}
    @PostMapping("/design-sessions/{id}/finish") Object finish(@PathVariable String id,@RequestBody JsonNode b){return s.finish(id,b);}
}
