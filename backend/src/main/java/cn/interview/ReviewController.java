package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
class ReviewController {
    final ReviewService service;
    ReviewController(ReviewService service) {this.service=service;}
    @GetMapping("/review-import-preview") Object preview(@RequestParam String sessionId) {return service.preview(sessionId);}
    @PostMapping("/review-imports") Object importReport(@RequestBody JsonNode body) {return V2Responses.created("REVIEW_IMPORT","LOCAL_USER",body,()->service.importReport(body));}
    @GetMapping("/review-items") Object list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String status) {return service.list(cursor,limit,status);}
    @GetMapping("/review-item-for-source") Object source(@RequestParam String sessionId,@RequestParam int questionIndex){service.db.enabled();var ids=service.db.legacy.jdbc.query("SELECT id FROM v2_review_items WHERE source_session_id=? AND question_index=?",(rs,n)->rs.getString(1),sessionId,questionIndex);var out=service.db.object();if(ids.isEmpty())out.putNull("id");else out.put("id",ids.getFirst());return out;}
    @GetMapping("/review-items/{id}") Object item(@PathVariable String id) {return service.db.get("v2_review_items",id);}
    @PostMapping("/review-items/{id}/actions") Object action(@PathVariable String id,@RequestBody JsonNode body) {return service.action(id,body);}
    @GetMapping("/review-items/{id}/history") Object history(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return service.ops.history("v2_review_items",id,cursor,limit);}
    @GetMapping("/review-items/{id}/history/{operationId}") Object historyDetail(@PathVariable String id,@PathVariable String operationId){return service.ops.historyDetail("v2_review_items",id,operationId);}
    @GetMapping("/skills") Object skills() {return service.skills();}
    @PostMapping("/skills/{id}/self-ratings") Object rating(@PathVariable String id,@RequestBody JsonNode body) {return service.rating(id,body);}
    @GetMapping("/skills/{id}/events") Object events(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit) {
        var canonical=service.skillIds(service.db.array().add(id)).get(0).asText();
        return service.db.page("v2_skill_events",cursor,limit,null,"skill_id",canonical);
    }
    @GetMapping("/operations/{id}") Object operation(@PathVariable String id) {return service.ops.safe(id);}
}
