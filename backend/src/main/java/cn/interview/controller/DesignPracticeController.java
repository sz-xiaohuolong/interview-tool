package cn.interview.controller;

import cn.interview.service.DesignPracticeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({"/api/training", "/api/v2"})
class DesignPracticeController {
    final DesignPracticeService s;DesignPracticeController(DesignPracticeService s){this.s=s;}
    @GetMapping("/design-scenarios") Object scenarios(){return s.availableScenarios();}
    @PostMapping("/design-sessions") Object create(@RequestBody JsonNode b){return ApiResponses.created("DESIGN_SESSION","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/design-sessions") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.list(cursor,limit);}
    @GetMapping("/design-sessions/{id}") Object get(@PathVariable String id){return s.get(id);}
    @PutMapping("/design-sessions/{id}/draft") Object draft(@PathVariable String id,@RequestBody JsonNode b){return s.draft(id,b);}
    @PostMapping("/design-sessions/{id}/submit") Object submit(@PathVariable String id,@RequestBody JsonNode b){return s.submit(id,b);}
    @PostMapping("/design-sessions/{id}/advance") Object advance(@PathVariable String id,@RequestBody JsonNode b){return s.advance(id,b);}
    @PostMapping("/design-sessions/{id}/reopen") Object reopen(@PathVariable String id,@RequestBody JsonNode b){return s.reopen(id,b);}
    @PostMapping("/design-sessions/{id}/finish") Object finish(@PathVariable String id,@RequestBody JsonNode b){return s.finish(id,b);}
}
