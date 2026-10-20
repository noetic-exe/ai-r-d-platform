package com.aird;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class JobController {
    final Store s;final Access a;final FileStorage files;
    JobController(Store s,Access a,FileStorage files){this.s=s;this.a=a;this.files=files;}
    record Run(@NotBlank @Size(max=120) String name,@Min(1) long dataset_version_id,Long experiment_id,@NotBlank String target_column,@NotBlank String model,Map<String,Object> parameters,@Min(0) int seed,@DecimalMin("0.1") @DecimalMax("0.4") double test_fraction,@NotBlank @Size(max=100) String submission_key,Long parent_job_id) {}
    record Experiment(@NotBlank @Size(max=120) String name,@Size(max=4000) String notes,@Size(max=500) String tags) {}
    @GetMapping("/models") Object models(){return List.of(Map.of("id","logistic_regression","name","Logistic regression","task","classification"),Map.of("id","random_forest","name","Random forest","task","classification"),Map.of("id","baseline","name","Majority baseline","task","classification"),Map.of("id","linear_regression","name","Linear regression","task","regression"));}
    @GetMapping("/jobs") Object jobs(@RequestParam(required=false) Long project) {
        String base="SELECT j.*,d.name AS dataset_name,v.version_number,p.name AS project_name,e.name AS experiment_name FROM jobs j JOIN dataset_versions v ON v.id=j.dataset_version_id JOIN datasets d ON d.id=v.dataset_id JOIN projects p ON p.id=j.project_id JOIN experiments e ON e.id=j.experiment_id";
        List<Map<String,Object>> rows;
        if(project!=null){a.project(project,false);rows=s.list(base+" WHERE j.project_id=? ORDER BY j.id DESC LIMIT 200",project);}
        else if(a.admin())rows=s.list(base+" ORDER BY j.id DESC LIMIT 200");
        else rows=s.list(base+" WHERE EXISTS(SELECT 1 FROM memberships m WHERE m.project_id=j.project_id AND m.user_id=?) ORDER BY j.id DESC LIMIT 200",a.uid());
        rows.forEach(this::publicJob);return rows;
    }
    @PostMapping("/projects/{id}/training-jobs") @ResponseStatus(HttpStatus.ACCEPTED) @Transactional Object submit(@PathVariable long id,@Valid @RequestBody Run in) {
        a.project(id,true);var project=s.one("SELECT * FROM projects WHERE id=? FOR UPDATE",id); Problem.require("active".equals(project.get("status")),409,"This project is archived.");
        var existing=s.list("SELECT * FROM jobs WHERE project_id=? AND user_id=? AND submission_key=?",id,a.uid(),in.submission_key());
        if(!existing.isEmpty())return publicJob(existing.getFirst());
        s.one("SELECT id FROM worker_slot WHERE id=1 FOR UPDATE");
        Problem.require(s.count("SELECT COUNT(*) FROM jobs WHERE state IN ('queued','running')")<32,409,"The training queue is full. Try again after a job finishes.");
        if(project.get("compute_resource_id")!=null)s.one("SELECT id FROM resources WHERE id=? AND type='compute' AND active=TRUE",project.get("compute_resource_id"));
        long runtime=s.count("SELECT COALESCE(SUM(runtime_ms),0) FROM jobs WHERE project_id=?",id);
        long reserved=s.count("SELECT COUNT(*) FROM jobs WHERE project_id=? AND state IN ('queued','running')",id)*600000;
        Problem.require(runtime+reserved+600000<=Store.number(project,"runtime_quota_seconds")*1000,409,"Project runtime quota exceeded (each active job reserves up to 10 minutes).");
        var version=a.version(in.dataset_version_id(),true);
        Problem.require(Store.number(version,"project_id")==id && !Boolean.TRUE.equals(version.get("archived")),422,"Choose an available dataset version in this project.");
        Problem.require(Set.of("logistic_regression","random_forest","linear_regression","baseline").contains(in.model()),422,"Unsupported model.");
        var schema=s.decode(version.get("schema_json"));
        var columns=(List<?>)schema.get("columns");
        Problem.require(columns.stream().anyMatch(c->in.target_column().equals(((Map<?,?>)c).get("name"))),422,"Target column does not exist.");
        var params=in.parameters()==null?Map.<String,Object>of():in.parameters();
        for(var entry:params.entrySet()){
            Problem.require(entry.getValue() instanceof Number,422,"Model parameters must be numbers.");
            double value=((Number)entry.getValue()).doubleValue();
            Problem.require(Double.isFinite(value) && value==Math.rint(value),422,"Model parameters must be integers.");
            switch(entry.getKey()){
                case "epochs" -> Problem.require(Set.of("logistic_regression","linear_regression").contains(in.model()) && value>=10 && value<=300,422,"Epochs must be 10–300 for a linear model.");
                case "n_estimators" -> Problem.require(in.model().equals("random_forest") && value>=5 && value<=100,422,"Random forest needs 5–100 trees.");
                case "max_depth" -> Problem.require(in.model().equals("random_forest") && value>=2 && value<=12,422,"Tree depth must be 2–12.");
                default -> throw new Problem(422,"Unknown model parameter.");
            }
        }
        if(in.parent_job_id()!=null)Problem.require(Store.number(a.job(in.parent_job_id()),"project_id")==id,422,"Parent run must belong to this project.");
        long eid;
        if(in.experiment_id()!=null){var e=a.experiment(in.experiment_id(),true);Problem.require(Store.number(e,"project_id")==id,422,"Experiment must belong to this project.");eid=in.experiment_id();}
        else eid=s.insert("INSERT INTO experiments(project_id,name,notes,created_at) VALUES(?,?,?,?)",id,in.name(),"",Store.now());
        long job=s.insert("INSERT INTO jobs(project_id,user_id,experiment_id,dataset_version_id,name,model,target_column,parameters_json,seed,test_fraction,submission_key,parent_job_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,a.uid(),eid,in.dataset_version_id(),in.name(),in.model(),in.target_column(),s.encode(params),in.seed(),in.test_fraction(),in.submission_key(),in.parent_job_id(),Store.now());
        s.event(job,"queued","Accepted into the durable CPU queue.");
        return publicJob(s.one("SELECT * FROM jobs WHERE id=?",job));
    }
    @GetMapping("/training-jobs/{id}") Object job(@PathVariable long id){var j=a.job(id);j.put("events",s.list("SELECT stage,message,created_at FROM job_events WHERE job_id=? ORDER BY id",id));return publicJob(j);}
    @GetMapping("/training-jobs/{id}/artifact") ResponseEntity<?> artifact(@PathVariable long id) {var j=a.job(id);Problem.require("completed".equals(j.get("state")) && j.get("artifact_key")!=null,409,"A completed artifact is not yet available.");return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"model-"+id+".json\"").contentType(MediaType.APPLICATION_JSON).body(new FileSystemResource(files.path(j.get("artifact_key").toString())));}
    @PostMapping("/training-jobs/{id}/cancel") @Transactional Object cancel(@PathVariable long id){var j=a.job(id);a.project(Store.number(j,"project_id"),true);s.one("SELECT id FROM projects WHERE id=? FOR UPDATE",j.get("project_id"));int changed=s.db.update("UPDATE jobs SET state='cancelled',stage='cancelled',finished_at=?,cancel_requested=TRUE WHERE id=? AND state='queued'",Store.now(),id);if(changed>0)s.event(id,"cancelled","Cancelled before execution.");else{Problem.require(s.db.update("UPDATE jobs SET cancel_requested=TRUE WHERE id=? AND state='running'",id)>0,409,"This run has already finished.");s.event(id,"running","Cancellation requested.");}return Map.of("message",changed>0?"Job cancelled.":"Cancellation requested.");}
    @GetMapping("/projects/{id}/experiments") Object experiments(@PathVariable long id){a.project(id,false);return s.list("SELECT * FROM experiments WHERE project_id=? ORDER BY id DESC",id);}
    @PostMapping("/projects/{id}/experiments") Object experiment(@PathVariable long id,@Valid @RequestBody Experiment in){a.project(id,true);long eid=s.insert("INSERT INTO experiments(project_id,name,notes,tags,created_at) VALUES(?,?,?,?,?)",id,in.name(),Objects.requireNonNullElse(in.notes(),""),Objects.requireNonNullElse(in.tags(),""),Store.now());return Map.of("id",eid);}
    @PatchMapping("/experiments/{id}") Object annotate(@PathVariable long id,@Valid @RequestBody Experiment in){a.experiment(id,true);s.db.update("UPDATE experiments SET name=?,notes=?,tags=? WHERE id=?",in.name(),Objects.requireNonNullElse(in.notes(),""),Objects.requireNonNullElse(in.tags(),""),id);return Map.of("message","Experiment updated.");}
    @GetMapping("/experiments/compare") Object compare(@RequestParam String jobs){
        var ids=Arrays.stream(jobs.split(",")).map(Long::parseLong).distinct().toList();Problem.require(ids.size()>=2 && ids.size()<=8,422,"Select 2–8 completed runs.");
        var rows=new ArrayList<Map<String,Object>>();for(long id:ids){var j=a.job(id);Problem.require("completed".equals(j.get("state")),422,"Only completed runs can be compared.");rows.add(publicJob(j));}
        var first=rows.getFirst();boolean compatible=rows.stream().allMatch(j->List.of("dataset_version_id","target_column","seed","test_fraction").stream().allMatch(k->Objects.equals(first.get(k),j.get(k))) && (j.get("model").equals("linear_regression")==first.get("model").equals("linear_regression")));
        return Map.of("compatible",compatible,"message",compatible?"Same dataset version, target, seed, split and metric definitions.":"Incompatible runs: dataset version, target, seed, split or task differ. Metrics should not be ranked.","runs",rows);
    }
    @GetMapping("/experiments/export.csv") ResponseEntity<String> export(@RequestParam String jobs){
        var ids=Arrays.stream(jobs.split(",")).map(Long::parseLong).distinct().toList();Problem.require(!ids.isEmpty() && ids.size()<=200,422,"Select 1–200 runs.");
        var rows=new ArrayList<List<?>>();rows.add(List.of("run_id","name","model","state","dataset_version","seed","test_fraction","runtime_ms","metrics"));
        for(long id:ids){var j=a.job(id);rows.add(Arrays.asList(id,j.get("name"),j.get("model"),j.get("state"),j.get("dataset_version_id"),j.get("seed"),j.get("test_fraction"),j.get("runtime_ms"),j.get("metrics_json")));}
        return Reports.csv("experiments.csv",rows);
    }
    Map<String,Object> publicJob(Map<String,Object> j){j.put("parameters",s.decode(j.remove("parameters_json")));j.put("metrics",s.decode(j.remove("metrics_json")));j.put("has_artifact",j.get("artifact_key")!=null);j.remove("artifact_key");return j;}
}
