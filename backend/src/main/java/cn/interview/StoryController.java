package cn.interview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v2/stories")
class StoryController {
    final StoryService s;StoryController(StoryService s){this.s=s;}
    @PostMapping Object create(@RequestBody JsonNode b){return V2Responses.created("STORY_CREATE","LOCAL_USER",b,()->s.create(b));}
    @GetMapping Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){var page=s.db.page("v2_stories",cursor,limit,null);for(JsonNode row:page.path("items"))((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(java.util.List.of("fields","protocol"));return page;}
    @GetMapping("/{id}") Object get(@PathVariable String id){return s.view(s.db.get("v2_stories",id));}
    @PostMapping("/{id}/versions") Object save(@PathVariable String id,@RequestBody JsonNode b){return V2Responses.created("STORY_VERSION",id,b,()->s.save(id,b));}
    @PostMapping("/{id}/confirm") Object confirm(@PathVariable String id,@RequestBody JsonNode b){return s.confirm(id,b);}
    @PostMapping("/{id}/archive") Object archive(@PathVariable String id,@RequestBody JsonNode b){return s.archive(id,b);}
    @GetMapping("/{id}/versions") Object versions(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.history(id,cursor,limit);}
    @GetMapping("/{id}/versions/{versionId}") Object version(@PathVariable String id,@PathVariable String versionId){return s.getVersion(id,versionId);}
    @PostMapping("/{id}/input-preparations") Object prepare(@PathVariable String id,@RequestBody JsonNode b){return V2Responses.created("STORY_INPUT",id,b,()->s.prepare(id,b));}
    @PostMapping("/{id}/draft-generation") Object generate(@PathVariable String id,@RequestBody JsonNode b){return s.generate(id,b);}
    @GetMapping("/question-templates") Object templates(){return StoryService.QUESTIONS;}
}
