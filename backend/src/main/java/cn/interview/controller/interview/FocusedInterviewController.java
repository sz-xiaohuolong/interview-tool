package cn.interview.controller.interview;

import cn.interview.service.interview.FocusedInterviewService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class FocusedInterviewController {
    final FocusedInterviewService service;
    public FocusedInterviewController(FocusedInterviewService service){this.service=service;}
    @GetMapping("/health") public Map<String,Object> health(){return service.health();}
    @GetMapping("/sessions") public List<ObjectNode> list(){return service.list();}
    @PostMapping("/sessions") public ObjectNode create(@RequestBody JsonNode body){return service.create(body);}
    @GetMapping("/sessions/{id}") public ObjectNode session(@PathVariable String id){return service.get(id);}
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){service.delete(id);}
    @PostMapping("/sessions/{id}/answers") public ObjectNode answer(@PathVariable String id,@RequestBody JsonNode body){return service.answer(id,body);}
    @PostMapping("/sessions/{id}/retry") public ObjectNode retry(@PathVariable String id){return service.retry(id);}
    @PostMapping("/sessions/{id}/finish") public ObjectNode finish(@PathVariable String id){return service.finish(id);}
    @GetMapping("/sessions/{id}/report") public ObjectNode report(@PathVariable String id){return service.report(id,false);}
    @PostMapping("/sessions/{id}/report") public ObjectNode createReport(@PathVariable String id){return service.report(id,true);}
    @PostMapping("/predictions") public ObjectNode predict(@RequestBody JsonNode body){return service.predict(body);}
    @GetMapping("/predictions") public List<ObjectNode> predictions(@RequestParam(required=false)String resumeId){return service.predictions(resumeId);}
}
