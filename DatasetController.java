package com.aird;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class DatasetController {
    final Store s;final Access a;final FileStorage files;
    DatasetController(Store s,Access a,FileStorage files){this.s=s;this.a=a;this.files=files;}
    record Metadata(@NotBlank @Size(max=120) String name,@Size(max=4000) String description,@Size(max=1000) String source,@Size(max=200) String license){}
    @GetMapping("/projects/{id}/datasets") Object datasets(@PathVariable long id) {
        a.project(id,false);
        var rows=s.list("SELECT d.*,v.id AS version_id,v.version_number,v.row_count,v.column_count,v.bytes,v.checksum,v.archived FROM datasets d JOIN dataset_versions v ON v.dataset_id=d.id WHERE d.project_id=? AND v.version_number=(SELECT MAX(v2.version_number) FROM dataset_versions v2 WHERE v2.dataset_id=d.id) ORDER BY d.id DESC",id);
        return rows;
    }
    @PostMapping("/projects/{id}/datasets") @Transactional Object upload(@PathVariable long id,@RequestParam String name,@RequestParam(defaultValue="") String description,@RequestParam(defaultValue="") String source,@RequestParam(defaultValue="") String license,@RequestParam MultipartFile file) throws IOException {
        a.project(id,true);validateMetadata(new Metadata(name,description,source,license));
        return addVersion(id,null,new Metadata(name,description,source,license),file);
    }
    @PostMapping("/datasets/{id}/versions") @Transactional Object version(@PathVariable long id,@RequestParam MultipartFile file) throws IOException {
        var d=s.one("SELECT * FROM datasets WHERE id=?",id);long project=Store.number(d,"project_id");a.project(project,true);
        return addVersion(project,id,null,file);
    }
    private Object addVersion(long project,Long dataset,Metadata metadata,MultipartFile file) throws IOException {
        var p=s.one("SELECT * FROM projects WHERE id=? FOR UPDATE",project); Problem.require("active".equals(p.get("status")),409,"This project is archived.");
        if(p.get("storage_resource_id")!=null) s.one("SELECT id FROM resources WHERE id=? AND type='storage' AND active=TRUE",p.get("storage_resource_id"));
        long used=usedStorage(s,project);
        Problem.require(used+file.getSize()<=Store.number(p,"storage_quota_mb")*1024*1024,409,"Project storage quota exceeded.");
        var temp=files.receive(file);
        try {
            var table=files.read(temp);var schema=files.summary(table);long bytes=Files.size(temp);String checksum=files.checksum(temp);
            long did=dataset==null?s.insert("INSERT INTO datasets(project_id,owner_id,name,description,source,license,created_at) VALUES(?,?,?,?,?,?,?)",project,a.uid(),metadata.name().trim(),metadata.description(),metadata.source(),metadata.license(),Store.now()):dataset;
            long version=s.count("SELECT COALESCE(MAX(version_number),0)+1 FROM dataset_versions WHERE dataset_id=?",did);
            String key=UUID.randomUUID()+".csv";files.publish(temp,key);
            long vid=s.insert("INSERT INTO dataset_versions(dataset_id,version_number,object_key,checksum,bytes,row_count,column_count,schema_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",did,version,key,checksum,bytes,table.rows().size(),table.headers().size(),s.encode(schema),Store.now());
            return Map.of("id",did,"version_id",vid,"version_number",version,"message","CSV validated and saved.");
        } finally {Files.deleteIfExists(temp);}
    }
    static long usedStorage(Store s,long project) {
        return s.count("SELECT COALESCE(SUM(v.bytes),0) FROM dataset_versions v JOIN datasets d ON d.id=v.dataset_id WHERE d.project_id=?",project)+s.count("SELECT COALESCE(SUM(artifact_bytes),0) FROM jobs WHERE project_id=?",project);
    }
    @GetMapping("/datasets/{id}") Object detail(@PathVariable long id) {
        var d=s.one("SELECT * FROM datasets WHERE id=?",id);a.project(Store.number(d,"project_id"),false);
        d.put("versions",s.list("SELECT id,version_number,checksum,bytes,row_count,column_count,archived,created_at FROM dataset_versions WHERE dataset_id=? ORDER BY version_number DESC",id));return d;
    }
    @PatchMapping("/datasets/{id}") Object edit(@PathVariable long id,@Valid @RequestBody Metadata in) {var d=s.one("SELECT * FROM datasets WHERE id=?",id);a.project(Store.number(d,"project_id"),true);s.db.update("UPDATE datasets SET name=?,description=?,source=?,license=? WHERE id=?",in.name(),Objects.requireNonNullElse(in.description(),""),Objects.requireNonNullElse(in.source(),""),Objects.requireNonNullElse(in.license(),""),id);return Map.of("message","Dataset metadata updated.");}
    @GetMapping("/dataset-versions/{id}/preview") Object preview(@PathVariable long id) { var v=a.version(id,false);var out=s.decode(v.get("schema_json"));out.put("checksum",v.get("checksum"));out.put("rows",v.get("row_count"));out.put("bytes",v.get("bytes"));return out; }
    @GetMapping("/dataset-versions/{id}/download") ResponseEntity<?> download(@PathVariable long id) {var v=a.version(id,false);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"dataset-"+id+".csv\"").contentType(MediaType.parseMediaType("text/csv")).body(new FileSystemResource(files.path(v.get("object_key").toString())));}
    @DeleteMapping("/dataset-versions/{id}") @Transactional Object delete(@PathVariable long id) {
        var v=a.version(id,true);s.one("SELECT id FROM projects WHERE id=? FOR UPDATE",v.get("project_id"));
        Problem.require(s.count("SELECT COUNT(*) FROM jobs WHERE dataset_version_id=? AND state IN ('queued','running')",id)==0,409,"This dataset is being used by an active job.");
        if(s.count("SELECT COUNT(*) FROM jobs WHERE dataset_version_id=?",id)>0) {s.db.update("UPDATE dataset_versions SET archived=TRUE WHERE id=?",id);return Map.of("message","Version archived to preserve experiment history.");}
        s.db.update("DELETE FROM dataset_versions WHERE id=?",id);files.deleteAfterCommit(v.get("object_key").toString());return Map.of("message","Unreferenced version deleted.");
    }
    private void validateMetadata(Metadata m){Problem.require(m.name()!=null && !m.name().isBlank() && m.name().length()<=120 && m.description().length()<=4000 && m.source().length()<=1000 && m.license().length()<=200,422,"Check dataset name and metadata lengths.");}
}
