package cn.interview.controller;

import cn.interview.service.ModelTaskRunner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({"/api/training/operations", "/api/v2/operations"})
class OperationController {
    final ModelTaskRunner runner;OperationController(ModelTaskRunner runner){this.runner=runner;}
    @PostMapping("/{id}/retry") Object retry(@PathVariable String id,@RequestBody JsonNode body){return runner.retry(id,body);}
}
