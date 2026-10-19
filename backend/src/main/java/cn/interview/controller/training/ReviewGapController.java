package cn.interview.controller.training;

import cn.interview.service.training.ReviewGapService;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/review-gaps/{sourceKind}/{sourceId}")
class ReviewGapController {
    private final ReviewGapService service;
    ReviewGapController(ReviewGapService service) { this.service = service; }
    @GetMapping Object list(@PathVariable String sourceKind, @PathVariable String sourceId) { return service.list(sourceKind, sourceId); }
    @PostMapping("/{questionId}") Object create(@PathVariable String sourceKind, @PathVariable String sourceId, @PathVariable String questionId, @RequestBody JsonNode body) { return service.create(sourceKind, sourceId, questionId, body); }
}
