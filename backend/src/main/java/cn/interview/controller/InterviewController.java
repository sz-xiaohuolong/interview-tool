package cn.interview.controller;

import cn.interview.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InterviewController {
  private final InterviewService service;

  public InterviewController(InterviewService service) {
    this.service = service;
  }

  @GetMapping({"/interviews/status", "/v3/status"})
  public Object status() {
    return service.health();
  }

  @GetMapping({"/interviews", "/v3/sessions"})
  public Object list() {
    return service.list();
  }

  @GetMapping({"/interviews/{id}", "/v3/sessions/{id}"})
  public Object get(@PathVariable String id) {
    return service.get(id);
  }

  @DeleteMapping({"/interviews/{id}", "/v3/sessions/{id}"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    service.delete(id);
  }

  @PostMapping({"/interviews", "/v3/sessions"})
  public Object create(@RequestBody JsonNode body) {
    return service.create(body);
  }

  @PostMapping({"/interviews/{id}/{action}", "/v3/sessions/{id}/{action}"})
  public Object action(
      @PathVariable String id, @PathVariable String action, @RequestBody JsonNode body) {
    return service.action(id, action, body);
  }
}
