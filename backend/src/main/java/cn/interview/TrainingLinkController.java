package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/training-links")
class TrainingLinkController {
    final TrainingLinkService service;
    TrainingLinkController(TrainingLinkService service) {this.service=service;}
    @PostMapping Object link(@RequestBody JsonNode body) {return V2Responses.created("TRAINING_LINK","LOCAL_USER",body,()->service.link(body));}
    @GetMapping Object bySession(@RequestParam String sessionId){service.db.enabled();var out=service.db.array();for(String id:service.db.ids("v2_training_links","session_id",sessionId)){var row=service.db.get("v2_training_links",id);var dto=service.db.object();for(String key:java.util.List.of("id","sourceKind","sourceId","sprintId","actionId","sessionId"))if(row.has(key))dto.set(key,row.path(key));out.add(dto);}return out;}
    @GetMapping("/{id}") Object get(@PathVariable String id) {return service.db.get("v2_training_links",id);}
}
