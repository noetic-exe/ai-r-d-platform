package com.aird;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class Reports {
    final Store s;final Access a;final Worker worker;
    Reports(Store s,Access a,Worker worker){this.s=s;this.a=a;this.worker=worker;}
    @GetMapping("/health") Object health(){return Map.of("status","ok","database",s.count("SELECT COUNT(*) FROM worker_slot")==1?"ok":"error","worker_lease",s.one("SELECT lease_until FROM worker_slot WHERE id=1").get("lease_until"),"worker",System.currentTimeMillis()-s.count("SELECT heartbeat FROM worker_slot WHERE id=1")<20000?"online":"offline","time",Store.now());}
    @GetMapping("/usage") Object usage(@RequestParam(required=false) Long project,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(required=false) Long resource){
        a.requireAdmin();return usageRows(project,from,to,resource);
    }
    List<Map<String,Object>> usageRows(Long project,String from,String to,Long resource){
        if(!from.isBlank())java.time.LocalDate.parse(from);if(!to.isBlank())java.time.LocalDate.parse(to);
        var rows=new ArrayList<Map<String,Object>>();
        for(var p:s.list("SELECT * FROM projects ORDER BY id")){
            long id=s.id(p);if(project!=null && project!=id)continue;
            if(resource!=null && !Objects.equals(p.get("compute_resource_id"),resource) && !Objects.equals(p.get("storage_resource_id"),resource))continue;
            String where="project_id=?";var args=new ArrayList<Object>();args.add(id);
            if(!from.isBlank()){where+=" AND created_at>=?";args.add(from);}
            if(!to.isBlank()){where+=" AND created_at<?";args.add(java.time.LocalDate.parse(to).plusDays(1).toString());}
            long runtime=s.count("SELECT COALESCE(SUM(runtime_ms),0) FROM jobs WHERE "+where,args.toArray());
            long runs=s.count("SELECT COUNT(*) FROM jobs WHERE "+where,args.toArray());
            var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("project",p.get("name"));row.put("runtime_ms",runtime);row.put("runs",runs);row.put("storage_bytes",DatasetController.usedStorage(s,id));row.put("storage_quota_mb",p.get("storage_quota_mb"));row.put("runtime_quota_seconds",p.get("runtime_quota_seconds"));rows.add(row);
        }return rows;
    }
    @GetMapping("/usage/export.csv") ResponseEntity<String> usageExport(@RequestParam(required=false) Long project,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(required=false) Long resource){
        a.requireAdmin();var rows=new ArrayList<List<?>>();rows.add(List.of("project","runs","elapsed_runtime_ms","current_storage_bytes","storage_quota_mb","runtime_quota_seconds"));
        for(var r:usageRows(project,from,to,resource))rows.add(List.of(r.get("project"),r.get("runs"),r.get("runtime_ms"),r.get("storage_bytes"),r.get("storage_quota_mb"),r.get("runtime_quota_seconds")));
        return csv("usage.csv",rows);
    }
    static ResponseEntity<String> csv(String filename,List<List<?>> rows){
        var text=new StringBuilder("\uFEFF");
        for(var row:rows){var cells=new ArrayList<String>();for(var value:row){String str=value==null?"":value.toString();if(str.matches("^[=+@\\-].*"))str="'"+str;cells.add("\""+str.replace("\"","\"\"")+"\"");}text.append(String.join(",",cells)).append("\r\n");}
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"").body(text.toString());
    }
}
