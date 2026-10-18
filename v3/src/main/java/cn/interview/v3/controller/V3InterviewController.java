package cn.interview.v3.controller;

import cn.interview.v3.service.V3InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3")
@ConditionalOnProperty(name = "app.v3.enabled", havingValue = "true")
public class V3InterviewController {
  private final V3InterviewService service;

  public V3InterviewController(V3InterviewService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public Object status() {
    return service.health();
  }

  @GetMapping("/sessions")
  public Object list() {
    return service.list();
  }

  @GetMapping("/sessions/{id}")
  public Object get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/sessions")
  public Object create(@RequestBody JsonNode body) {
    return service.create(body);
  }

  @PostMapping("/sessions/{id}/{action}")
  public Object action(
      @PathVariable String id, @PathVariable String action, @RequestBody JsonNode body) {
    return service.action(id, action, body);
  }
}
