package cn.interview.controller;

import cn.interview.entity.Material;
import cn.interview.service.MaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/materials")
public class MaterialController {
    final MaterialService service;
    public MaterialController(MaterialService service){this.service=service;}
    @GetMapping public List<Material> list(@RequestParam(required=false)String kind){return service.list(kind);}
    @PostMapping public Material create(@RequestBody JsonNode body){return service.create(body);}
    @PutMapping("/{id}") public Material update(@PathVariable String id,@RequestBody JsonNode body){return service.update(id,body);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id){service.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/upload") public Material upload(@RequestParam String kind,@RequestParam(required=false)String name,@RequestParam MultipartFile file){return service.upload(kind,name,file);}
    @PostMapping("/{id}/documents") public Material document(@PathVariable String id,@RequestParam MultipartFile file){return service.addDocument(id,file);}
    @DeleteMapping("/{id}/documents/{documentId}") public ResponseEntity<Void> deleteDocument(@PathVariable String id,@PathVariable String documentId){service.deleteDocument(id,documentId);return ResponseEntity.noContent().build();}
}
