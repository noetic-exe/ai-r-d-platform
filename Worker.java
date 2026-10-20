package com.aird;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class Worker {
    final Store s;final FileStorage files;final TransactionTemplate tx;final Trainer trainer=new Trainer();
    final String owner=UUID.randomUUID().toString();final AtomicReference<Long> active=new AtomicReference<>();
    @Value("${app.mode}") String mode;
    volatile long lastCheckMs=0;
    volatile long lastHeartbeat=System.currentTimeMillis();
    Worker(Store s,FileStorage files,TransactionTemplate tx){this.s=s;this.files=files;this.tx=tx;}
    @Scheduled(fixedDelay=700,initialDelay=1500) void poll(){
        if(mode.equals("api") || active.get()!=null)return;
        Long id=tx.execute(status->{
            var slot=s.one("SELECT * FROM worker_slot WHERE id=1 FOR UPDATE");
            long now=System.currentTimeMillis();if(Store.number(slot,"lease_until")>now)return null;
            var jobs=s.list("SELECT id FROM jobs WHERE state='queued' ORDER BY id LIMIT 1");
            if(jobs.isEmpty())return null;long job=s.id(jobs.getFirst());
            if(s.db.update("UPDATE jobs SET state='running',stage='loading',started_at=?,lease_until=? WHERE id=? AND state='queued'",Store.now(),now+60000,job)==0)return null;
            s.db.update("UPDATE worker_slot SET owner=?,lease_until=? WHERE id=1",owner,now+60000);s.event(job,"loading","Worker claimed job; loading immutable dataset.");return job;
        });
        if(id==null)return;active.set(id);lastCheckMs=0;long started=System.nanoTime();
        try{
            var job=s.one("SELECT * FROM jobs WHERE id=?",id);var v=s.one("SELECT v.*,d.project_id FROM dataset_versions v JOIN datasets d ON d.id=v.dataset_id WHERE v.id=?",job.get("dataset_version_id"));
            var p=s.one("SELECT * FROM projects WHERE id=?",job.get("project_id"));
            if(p.get("compute_resource_id")!=null)s.one("SELECT id FROM resources WHERE id=? AND active=TRUE AND type='compute'",p.get("compute_resource_id"));
            var table=files.read(files.path(v.get("object_key").toString()));
            Problem.require(files.checksum(files.path(v.get("object_key").toString())).equals(v.get("checksum")),422,"Dataset checksum verification failed.");
            var result=trainer.train(table,job.get("target_column").toString(),job.get("model").toString(),s.decode(job.get("parameters_json")),((Number)job.get("seed")).intValue(),((Number)job.get("test_fraction")).doubleValue(),stage->{
                check(id,started);s.db.update("UPDATE jobs SET stage=? WHERE id=? AND state='running'",stage,id);s.event(id,stage,stage.equals("training")?"Fitting preprocessing and estimator on the training split.":"Evaluating the held-out test split.");
            },()->check(id,started));
            var artifact=new LinkedHashMap<>(result.artifact());artifact.put("dataset_checksum",v.get("checksum"));artifact.put("dataset_version_id",job.get("dataset_version_id"));artifact.put("created_at",Store.now());
            String key=UUID.randomUUID()+".json";Path temp=Files.createTempFile(files.root,"model-",".tmp");
            try{
                Files.writeString(temp,s.encode(artifact));long bytes=Files.size(temp);String checksum=files.checksum(temp);
                tx.executeWithoutResult(status->{
                    var project=s.one("SELECT * FROM projects WHERE id=? FOR UPDATE",job.get("project_id"));check(id,started);
                    Problem.require(DatasetController.usedStorage(s,Store.number(job,"project_id"))+bytes<=Store.number(project,"storage_quota_mb")*1024*1024,409,"Model artifact would exceed project storage quota.");
                    try{files.publish(temp,key);}catch(java.io.IOException e){throw new IllegalStateException("Artifact could not be saved.");}
                    long runtime=(System.nanoTime()-started)/1000000;
                    int changed=s.db.update("UPDATE jobs SET state='completed',stage='completed',metrics_json=?,artifact_key=?,artifact_checksum=?,artifact_bytes=?,runtime_ms=?,finished_at=?,lease_until=0 WHERE id=? AND state='running' AND cancel_requested=FALSE",s.encode(result.metrics()),key,checksum,bytes,runtime,Store.now(),id);
                    if(changed==0)throw new CancellationException();s.event(id,"completed","Metrics and model artifact saved; elapsed execution "+runtime+" ms.");
                });
            }finally{Files.deleteIfExists(temp);}
        }catch(CancellationException e){finish(id,"cancelled","Cancelled cooperatively.",started);}
        catch(Exception e){String message=e instanceof Problem?e.getMessage():"Training failed: "+e.getClass().getSimpleName()+". Inspect dataset types and model settings.";finish(id,"failed",message,started);}
        finally{active.set(null);s.db.update("UPDATE worker_slot SET owner=NULL,lease_until=0 WHERE id=1 AND owner=?",owner);}
    }
    void check(long id,long started){
        if((System.nanoTime()-started)>600L*1000000000)throw new Problem(422,"Training exceeded the 10-minute timeout.");
        long now=System.currentTimeMillis();if(now-lastCheckMs<100)return;lastCheckMs=now;
        var j=s.one("SELECT state,cancel_requested FROM jobs WHERE id=?",id);
        if(!j.get("state").equals("running") || Boolean.TRUE.equals(j.get("cancel_requested")))throw new CancellationException();
        if((System.nanoTime()-started)>600L*1000000000)throw new Problem(422,"Training exceeded the 10-minute timeout.");
        var slot=s.one("SELECT owner,lease_until FROM worker_slot WHERE id=1");
        if(!owner.equals(slot.get("owner")) || Store.number(slot,"lease_until")<System.currentTimeMillis())throw new CancellationException();
    }
    void finish(long id,String state,String error,long started){
        tx.executeWithoutResult(status->{int count=s.db.update("UPDATE jobs SET state=?,stage=?,error=?,runtime_ms=?,finished_at=?,lease_until=0 WHERE id=? AND state='running'",state,state,error,(System.nanoTime()-started)/1000000,Store.now(),id);if(count>0)s.event(id,state,error);});
    }
    @Scheduled(fixedDelay=5000) void heartbeat(){if(mode.equals("api"))return;lastHeartbeat=System.currentTimeMillis();s.db.update("UPDATE worker_slot SET heartbeat=? WHERE id=1",lastHeartbeat);Long id=active.get();if(id!=null){int renewed=s.db.update("UPDATE worker_slot SET lease_until=? WHERE id=1 AND owner=?",lastHeartbeat+60000,owner);if(renewed>0)s.db.update("UPDATE jobs SET lease_until=? WHERE id=? AND state='running'",lastHeartbeat+60000,id);}}
    @Scheduled(fixedDelay=10000,initialDelay=10000) void reconcile(){
        if(mode.equals("api"))return;
        for(var j:s.list("SELECT id,started_at FROM jobs WHERE state='running' AND lease_until<?",System.currentTimeMillis())){
            long ms=Math.max(0,System.currentTimeMillis()-Instant.parse(j.get("started_at").toString()).toEpochMilli());
            tx.executeWithoutResult(status->{int count=s.db.update("UPDATE jobs SET state='failed',stage='failed',error=?,runtime_ms=?,finished_at=?,lease_until=0 WHERE id=? AND state='running' AND lease_until<?","Worker lease expired; execution was interrupted.",ms,Store.now(),j.get("id"),System.currentTimeMillis());if(count>0)s.event(s.id(j),"failed","Worker lease expired; execution was interrupted.");});
        }
    }
}
