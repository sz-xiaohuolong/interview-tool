package cn.interview.controller.training;

import cn.interview.common.ApiResponses;
import cn.interview.service.training.TrainingLinkService;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/training/training-links", "/api/v2/training-links"})
class TrainingLinkController {
    final TrainingLinkService service;
    TrainingLinkController(TrainingLinkService service) {this.service=service;}
    @PostMapping Object link(@RequestBody JsonNode body) {return ApiResponses.created("TRAINING_LINK","LOCAL_USER",body,()->service.link(body));}
    @GetMapping Object bySession(@RequestParam String sessionId){return service.bySession(sessionId);}
    @GetMapping("/{id}") Object get(@PathVariable String id) {return service.get(id);}
}
