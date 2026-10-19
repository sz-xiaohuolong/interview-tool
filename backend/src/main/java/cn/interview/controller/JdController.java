package cn.interview.controller;

import cn.interview.service.JdService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/training", "/api/v2"})
class JdController {
    final JdService s;JdController(JdService s){this.s=s;}
    @PostMapping("/jd-diagnostics/input-preparations") Object prepare(@RequestBody JsonNode b){return ApiResponses.created("JD_INPUT","LOCAL_USER",b,()->s.prepare(b));}
    @PostMapping("/jd-diagnostics") Object create(@RequestBody JsonNode b){return ApiResponses.created("JD_DIAGNOSTIC","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/jd-diagnostics") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.list(cursor,limit);}
    @GetMapping("/jd-diagnostics/{id}") Object get(@PathVariable String id){return s.get(id);}
    @PostMapping("/jd-diagnostics/{id}/confirm") Object confirm(@PathVariable String id,@RequestBody JsonNode b){return s.confirm(id,b);}
    @PostMapping("/sprints") Object createSprint(@RequestBody JsonNode b){return ApiResponses.created("SPRINT_CREATE","LOCAL_USER",b,()->s.sprint(b));}
    @GetMapping("/sprints") Object sprints(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.sprints(cursor,limit);}
    @GetMapping("/sprints/{id}") Object sprint(@PathVariable String id){return s.sprintView(id);}
    @PostMapping("/sprints/{id}/actions") Object action(@PathVariable String id,@RequestBody JsonNode b){return s.action(id,b);}
    @GetMapping("/sprints/{id}/diagnostic-diff") Object diff(@PathVariable String id,@RequestParam String diagnosticId){return s.diff(id,diagnosticId);}
    @GetMapping("/sprints/{id}/history") Object history(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.history(id,cursor,limit);}
    @GetMapping("/sprints/{id}/history/{operationId}") Object historyDetail(@PathVariable String id,@PathVariable String operationId){return s.historyDetail(id,operationId);}
    @GetMapping("/jd-diagnostics/{id}/completion-material") Object material(@PathVariable String id,@RequestParam String materialId){return s.completionMaterial(id,materialId);}
    @GetMapping("/available-materials") Object materials(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.availableMaterials(cursor,limit);}
    @GetMapping("/available-training-observations") Object observations(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.observations(cursor,limit);}
}
