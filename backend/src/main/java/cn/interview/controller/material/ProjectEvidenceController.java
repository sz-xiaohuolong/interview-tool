package cn.interview.controller.material;

import cn.interview.service.training.ProjectGapService;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/project-evidence/{sourceKind}/{sourceId}/gaps")
class ProjectEvidenceController {
    private final ProjectGapService service;
    ProjectEvidenceController(ProjectGapService service) { this.service = service; }
    @GetMapping Object list(@PathVariable String sourceKind, @PathVariable String sourceId) { return service.list(sourceKind, sourceId); }
    @GetMapping("/{checkpointId}") Object get(@PathVariable String sourceKind, @PathVariable String sourceId, @PathVariable String checkpointId) { return service.get(sourceKind, sourceId, checkpointId); }
    @PostMapping("/{checkpointId}") Object create(@PathVariable String sourceKind, @PathVariable String sourceId, @PathVariable String checkpointId, @RequestBody JsonNode body) { return service.create(sourceKind, sourceId, checkpointId, body); }
}
