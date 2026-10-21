package cn.interview.controller.training;

import cn.interview.common.ApiResponses;
import cn.interview.service.training.TrainingRecordRemovalService;
import cn.interview.service.training.ReviewService;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/training", "/api/v2"})
class ReviewController {
    final ReviewService service;
    final TrainingRecordRemovalService removal;
    ReviewController(ReviewService service,TrainingRecordRemovalService removal) {this.service=service;this.removal=removal;}
    @GetMapping("/review-import-preview") Object preview(@RequestParam String sessionId) {return service.preview(sessionId);}
    @PostMapping("/review-imports") Object importReport(@RequestBody JsonNode body) {return ApiResponses.created("REVIEW_IMPORT","LOCAL_USER",body,()->service.importReport(body));}
    @GetMapping("/review-items") Object list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String status) {return service.list(cursor,limit,status);}
    @GetMapping("/review-item-for-source") Object source(@RequestParam String sessionId,@RequestParam int questionIndex){return service.source(sessionId,questionIndex);}
    @DeleteMapping("/review-items/{id}") Object delete(@PathVariable String id,@RequestBody JsonNode body){return removal.review(id,body);}
    @GetMapping("/review-items/{id}") Object item(@PathVariable String id) {return service.get(id);}
    @PostMapping("/review-items/{id}/actions") Object action(@PathVariable String id,@RequestBody JsonNode body) {return service.action(id,body);}
    @GetMapping("/review-items/{id}/history") Object history(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return service.history(id,cursor,limit);}
    @GetMapping("/review-items/{id}/history/{operationId}") Object historyDetail(@PathVariable String id,@PathVariable String operationId){return service.historyDetail(id,operationId);}
    @GetMapping("/skills") Object skills() {return service.skills();}
    @PostMapping("/skills/{id}/self-ratings") Object rating(@PathVariable String id,@RequestBody JsonNode body) {return service.rating(id,body);}
    @GetMapping("/skills/{id}/events") Object events(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit) {return service.events(id,cursor,limit);}
    @GetMapping("/operations/{id}") Object operation(@PathVariable String id) {return service.operation(id);}
}
