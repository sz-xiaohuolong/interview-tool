package cn.interview.controller.job;

import cn.interview.entity.JobApplication;
import cn.interview.service.job.JobApplicationService;
import cn.interview.service.job.JobApplicationCsvService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/job-applications")
public class JobApplicationController {
    private final JobApplicationService service;
    private final JobApplicationCsvService csv;
    public JobApplicationController(JobApplicationService service,JobApplicationCsvService csv) { this.service=service;this.csv=csv; }
    @GetMapping("/csv/template") public ResponseEntity<byte[]> template() { return csvResponse(csv.template(),"job-applications-template.csv"); }
    @GetMapping("/csv/export") public ResponseEntity<byte[]> export() { return csvResponse(csv.export(),"job-applications.csv"); }
    @PostMapping("/csv/preview") public JobApplicationCsvService.Preview preview(@RequestParam MultipartFile file) { return csv.preview(file); }
    @PostMapping("/csv/import") public JobApplicationCsvService.ImportResult importFile(@RequestParam MultipartFile file,@RequestParam String previewToken) { return csv.importFile(file,previewToken); }
    private ResponseEntity<byte[]> csvResponse(byte[] bytes,String filename) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header("Content-Disposition","attachment; filename=\""+filename+"\"").header("Cache-Control","no-store").body(bytes);
    }
    @GetMapping public List<JobApplication> list(@RequestParam(required=false) String company,@RequestParam(required=false) String status,
        @RequestParam(defaultValue="false") String archived,@RequestParam(defaultValue="updatedAt") String sort,@RequestParam(defaultValue="desc") String direction) {
        return service.list(company,status,archived,sort,direction);
    }
    @GetMapping("/{id}") public JobApplication get(@PathVariable String id) { return service.get(id); }
    @PostMapping public JobApplication create(@RequestBody JsonNode body) { return service.create(body); }
    @PutMapping("/{id}") public JobApplication update(@PathVariable String id,@RequestBody JsonNode body) { return service.update(id,body); }
    @PatchMapping("/{id}/status") public JobApplication status(@PathVariable String id,@RequestBody JsonNode body) { return service.status(id,body); }
    @PatchMapping("/{id}/archive") public JobApplication archive(@PathVariable String id,@RequestBody JsonNode body) { return service.archive(id,body); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id);return ResponseEntity.noContent().build(); }
}
