package cn.interview;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
class JdController {
    final JdService s;JdController(JdService s){this.s=s;}
    @PostMapping("/jd-diagnostics/input-preparations") Object prepare(@RequestBody JsonNode b){return V2Responses.created("JD_INPUT","LOCAL_USER",b,()->s.inputs.prepareJd(b));}
    @PostMapping("/jd-diagnostics") Object create(@RequestBody JsonNode b){return V2Responses.created("JD_DIAGNOSTIC","LOCAL_USER",b,()->s.create(b));}
    @GetMapping("/jd-diagnostics") Object list(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.list(cursor,limit);}
    @GetMapping("/jd-diagnostics/{id}") Object get(@PathVariable String id){return s.view(s.db.get("v2_jd_diagnostics",id));}
    @PostMapping("/jd-diagnostics/{id}/confirm") Object confirm(@PathVariable String id,@RequestBody JsonNode b){return s.confirm(id,b);}
    @PostMapping("/sprints") Object createSprint(@RequestBody JsonNode b){return V2Responses.created("SPRINT_CREATE","LOCAL_USER",b,()->s.sprint(b));}
    @GetMapping("/sprints") Object sprints(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){var page=s.db.page("v2_sprints",cursor,limit,null);page.path("items").forEach(row->((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(java.util.List.of("actions","diagnosticHistory")));return page;}
    @GetMapping("/sprints/{id}") Object sprint(@PathVariable String id){return s.sprintView(id);}
    @PostMapping("/sprints/{id}/actions") Object action(@PathVariable String id,@RequestBody JsonNode b){return s.action(id,b);}
    @GetMapping("/sprints/{id}/diagnostic-diff") Object diff(@PathVariable String id,@RequestParam String diagnosticId){return s.diff(id,diagnosticId);}
    @GetMapping("/sprints/{id}/history") Object history(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return s.ops.history("v2_sprints",id,cursor,limit);}
    @GetMapping("/sprints/{id}/history/{operationId}") Object historyDetail(@PathVariable String id,@PathVariable String operationId){return s.ops.historyDetail("v2_sprints",id,operationId);}
    @GetMapping("/jd-diagnostics/{id}/completion-material") Object material(@PathVariable String id,@RequestParam String materialId){s.db.get("v2_jd_diagnostics",id);return s.completionMaterial(materialId);}
    @GetMapping("/available-materials") Object materials(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){
        s.db.enabled();int count=Math.max(1,Math.min(100,limit));var args=new java.util.ArrayList<Object>();String where="";
        if(cursor!=null&&!cursor.isBlank())try{String[] key=new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);if(key.length!=2)throw new IllegalArgumentException();where=" WHERE (created_at,id)<(?,?)";args.add(key[0]);args.add(key[1]);}catch(IllegalArgumentException e){throw V2Exception.invalid("资料游标无效");}
        args.add(count+1);var rows=s.db.legacy.jdbc.query("SELECT id,name,kind,created_at FROM materials"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",(rs,n)->s.db.object().put("id",rs.getString(1)).put("name",rs.getString(2)).put("kind",rs.getString(3)).put("createdAt",rs.getString(4)),args.toArray());
        var out=s.db.object();var items=out.putArray("items");rows.subList(0,Math.min(count,rows.size())).forEach(items::add);out.putNull("nextCursor");if(rows.size()>count){var last=items.get(items.size()-1);out.put("nextCursor",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((last.path("createdAt").asText()+"|"+last.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}return out;
    }
    @GetMapping("/available-training-observations") Object observations(@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){
        var page=s.db.page("v2_skill_events",cursor,limit,null);var items=s.db.array();for(JsonNode event:page.path("items"))try{items.add(s.inputs.trainingSource(event.path("id").asText()).path("observation"));}catch(ApiException ignored){/* 不接受的自评、归类与争议事件不作为选材。 */}page.set("items",items);return page;
    }
}
