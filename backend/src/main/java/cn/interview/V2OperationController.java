package cn.interview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v2/operations")
class V2OperationController {
    final V2ModelRunner runner;V2OperationController(V2ModelRunner runner){this.runner=runner;}
    @PostMapping("/{id}/retry") Object retry(@PathVariable String id,@RequestBody JsonNode body){return runner.retry(id,body);}
}
