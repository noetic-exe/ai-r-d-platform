package com.aird;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AdminController {
    final Store s; final Access a; final PasswordEncoder passwords;
    AdminController(Store s,Access a,PasswordEncoder passwords) { this.s=s; this.a=a; this.passwords=passwords; }
    record UserInput(@NotBlank @Size(max=100) String name,@NotBlank @Email @Size(max=200) String email,@NotBlank String role,boolean active,@Size(min=12,max=200) String password) {}
    record ProjectInput(@NotBlank @Size(max=120) String name,@Size(max=4000) String description,@Min(1) @Max(102400) long storage_quota_mb,@Min(600) @Max(10000000) long runtime_quota_seconds,Long compute_resource_id,Long storage_resource_id) {}
    record ResourceInput(@NotBlank @Size(max=100) String name,@NotBlank String type,@Min(1) @Max(1000000) long capacity,boolean active) {}
    record MemberInput(@Min(1) long user_id) {}
    @GetMapping("/users") Object users() { a.requireAdmin(); return s.list("SELECT id,name,email,role,active,must_change_password,created_at FROM users ORDER BY id"); }
    @GetMapping("/researchers") Object researchers() { return s.list("SELECT id,name,email FROM users WHERE active=TRUE AND role='researcher' ORDER BY name"); }
    @PostMapping("/users") @Transactional Object createUser(@Valid @RequestBody UserInput in) {
        a.requireAdmin(); validateUser(in); Problem.require(in.password()!=null,422,"A temporary password of at least 12 characters is required.");
        long id=s.insert("INSERT INTO users(name,email,role,active,password_hash,must_change_password,created_at) VALUES(?,?,?,?,?,TRUE,?)",in.name().trim(),in.email().trim().toLowerCase(Locale.ROOT),in.role(),in.active(),passwords.encode(in.password()),Store.now());
        s.audit(a.uid(),"Created user","user",id); return s.publicUser(s.one("SELECT * FROM users WHERE id=?",id));
    }
    @PatchMapping("/users/{id}") @Transactional Object updateUser(@PathVariable long id,@Valid @RequestBody UserInput in) {
        a.requireAdmin(); validateUser(in); protectAdmin(id,in.role(),in.active());
        var user=s.one("SELECT * FROM users WHERE id=?",id);
        s.db.update("UPDATE users SET name=?,email=?,role=?,active=?,password_hash=?,must_change_password=? WHERE id=?",in.name().trim(),in.email().trim().toLowerCase(Locale.ROOT),in.role(),in.active(),in.password()==null?user.get("password_hash"):passwords.encode(in.password()),in.password()==null?user.get("must_change_password"):true,id);
        SessionRevoker.revoke(id); s.audit(a.uid(),"Updated user","user",id); return Map.of("message","User updated.");
    }
    @DeleteMapping("/users/{id}") @Transactional Object deactivate(@PathVariable long id) {
        a.requireAdmin(); protectAdmin(id,"researcher",false);
        s.db.update("UPDATE users SET active=FALSE WHERE id=?",id); SessionRevoker.revoke(id);
        s.audit(a.uid(),"Deactivated user","user",id); return Map.of("message","User deactivated; research history retained.");
    }
    private void validateUser(UserInput in) { Problem.require(Set.of("admin","researcher").contains(in.role()),422,"Role must be admin or researcher."); }
    private void protectAdmin(long id,String role,boolean active) {
        var admins=s.list("SELECT id FROM users WHERE role='admin' AND active=TRUE ORDER BY id FOR UPDATE");
        boolean removes=admins.stream().anyMatch(u->s.id(u)==id) && (!active || !role.equals("admin"));
        Problem.require(!removes || admins.size()>1,409,"The last active administrator cannot be removed.");
    }
    @GetMapping("/projects") Object projects() {
        var rows=a.admin()?s.list("SELECT * FROM projects ORDER BY id"):s.list("SELECT p.* FROM projects p JOIN memberships m ON m.project_id=p.id WHERE m.user_id=? ORDER BY p.id",a.uid());
        for(var p:rows) {
            long id=s.id(p);
            p.put("members",s.list("SELECT u.id,u.name,u.email FROM users u JOIN memberships m ON m.user_id=u.id WHERE m.project_id=? AND u.active=TRUE ORDER BY u.name",id));
            p.put("dataset_count",s.count("SELECT COUNT(*) FROM datasets WHERE project_id=?",id));
            p.put("run_count",s.count("SELECT COUNT(*) FROM jobs WHERE project_id=?",id));
        } return rows;
    }
    @PostMapping("/projects") @Transactional Object createProject(@Valid @RequestBody ProjectInput in) {
        a.requireAdmin(); resource(in.compute_resource_id(),"compute"); resource(in.storage_resource_id(),"storage"); storageAllocation(in.storage_resource_id(),in.storage_quota_mb(),null);
        long id=s.insert("INSERT INTO projects(name,description,storage_quota_mb,runtime_quota_seconds,compute_resource_id,storage_resource_id,created_at) VALUES(?,?,?,?,?,?,?)",in.name().trim(),Objects.requireNonNullElse(in.description(),""),in.storage_quota_mb(),in.runtime_quota_seconds(),in.compute_resource_id(),in.storage_resource_id(),Store.now());
        s.audit(a.uid(),"Created project","project",id); return s.one("SELECT * FROM projects WHERE id=?",id);
    }
    @PatchMapping("/projects/{id}") @Transactional Object updateProject(@PathVariable long id,@Valid @RequestBody ProjectInput in) {
        a.requireAdmin(); a.project(id,false); s.one("SELECT id FROM projects WHERE id=? FOR UPDATE",id); Problem.require(in.storage_quota_mb()*1024*1024>=DatasetController.usedStorage(s,id),409,"Storage quota cannot be less than retained files."); resource(in.compute_resource_id(),"compute"); resource(in.storage_resource_id(),"storage"); storageAllocation(in.storage_resource_id(),in.storage_quota_mb(),id);
        s.db.update("UPDATE projects SET name=?,description=?,storage_quota_mb=?,runtime_quota_seconds=?,compute_resource_id=?,storage_resource_id=? WHERE id=?",in.name().trim(),Objects.requireNonNullElse(in.description(),""),in.storage_quota_mb(),in.runtime_quota_seconds(),in.compute_resource_id(),in.storage_resource_id(),id);
        s.audit(a.uid(),"Updated project and quotas","project",id); return Map.of("message","Project updated.");
    }
    @PostMapping("/projects/{id}/archive") @Transactional Object archive(@PathVariable long id) { a.requireAdmin(); a.project(id,false); s.one("SELECT id FROM projects WHERE id=? FOR UPDATE",id); Problem.require(s.count("SELECT COUNT(*) FROM jobs WHERE project_id=? AND state IN ('queued','running')",id)==0,409,"Finish or cancel active jobs before archiving."); s.db.update("UPDATE projects SET status='archived' WHERE id=?",id); s.audit(a.uid(),"Archived project","project",id); return Map.of("message","Project archived; history retained."); }
    @PostMapping("/projects/{id}/members") Object addMember(@PathVariable long id,@Valid @RequestBody MemberInput in) { a.requireAdmin(); a.project(id,true); s.one("SELECT id FROM users WHERE id=? AND active=TRUE",in.user_id()); if(s.count("SELECT COUNT(*) FROM memberships WHERE project_id=? AND user_id=?",id,in.user_id())==0) s.db.update("INSERT INTO memberships(project_id,user_id) VALUES(?,?)",id,in.user_id()); s.audit(a.uid(),"Added member","project",id); return Map.of("message","Member added."); }
    @DeleteMapping("/projects/{id}/members/{uid}") Object removeMember(@PathVariable long id,@PathVariable long uid) { a.requireAdmin(); a.project(id,false); s.db.update("DELETE FROM memberships WHERE project_id=? AND user_id=?",id,uid); s.audit(a.uid(),"Removed member","project",id); return Map.of("message","Membership removed immediately."); }
    @GetMapping("/resources") Object resources() { a.requireAdmin(); return s.list("SELECT * FROM resources ORDER BY id"); }
    @PostMapping("/resources") Object createResource(@Valid @RequestBody ResourceInput in) { a.requireAdmin(); validateResource(in); long id=s.insert("INSERT INTO resources(name,type,capacity,active,created_at) VALUES(?,?,?,?,?)",in.name(),in.type(),in.capacity(),in.active(),Store.now()); s.audit(a.uid(),"Registered resource","resource",id); return s.one("SELECT * FROM resources WHERE id=?",id); }
    @PatchMapping("/resources/{id}") @Transactional Object updateResource(@PathVariable long id,@Valid @RequestBody ResourceInput in) { a.requireAdmin(); validateResource(in); var current=s.one("SELECT * FROM resources WHERE id=? FOR UPDATE",id);
        long allocated=s.count("SELECT COUNT(*) FROM projects WHERE compute_resource_id=? OR storage_resource_id=?",id,id);
        Problem.require(current.get("type").equals(in.type()) || allocated==0,409,"An allocated resource cannot change type.");
        if(in.type().equals("storage")) Problem.require(in.capacity()>=s.count("SELECT COALESCE(SUM(storage_quota_mb),0) FROM projects WHERE storage_resource_id=?",id),409,"Capacity cannot be below allocated project quotas.");
        s.db.update("UPDATE resources SET name=?,type=?,capacity=?,active=? WHERE id=?",in.name(),in.type(),in.capacity(),in.active(),id); s.audit(a.uid(),"Updated resource","resource",id); return Map.of("message","Resource updated."); }
    @DeleteMapping("/resources/{id}") Object deleteResource(@PathVariable long id) { a.requireAdmin(); Problem.require(s.count("SELECT COUNT(*) FROM projects WHERE compute_resource_id=? OR storage_resource_id=?",id,id)==0,409,"Resource is allocated to a project. Disable it or remove its allocations first."); s.db.update("DELETE FROM resources WHERE id=?",id); s.audit(a.uid(),"Deleted unused resource","resource",id); return Map.of("message","Resource deleted."); }
    private void storageAllocation(Long resource,long quota,Long project) {
        if(resource==null)return;
        var r=s.one("SELECT capacity FROM resources WHERE id=? AND type='storage' AND active=TRUE FOR UPDATE",resource);
        long allocated=project==null?s.count("SELECT COALESCE(SUM(storage_quota_mb),0) FROM projects WHERE storage_resource_id=?",resource):s.count("SELECT COALESCE(SUM(storage_quota_mb),0) FROM projects WHERE storage_resource_id=? AND id<>?",resource,project);
        Problem.require(allocated+quota<=Store.number(r,"capacity"),409,"Storage pool capacity is fully allocated. Reduce the quota or choose another resource.");
    }
    private void validateResource(ResourceInput in) { Problem.require(Set.of("compute","storage").contains(in.type()),422,"Choose compute or storage."); }
    private void resource(Long id,String type) { if(id!=null) s.one("SELECT id FROM resources WHERE id=? AND type=? AND active=TRUE",id,type); }
    @GetMapping("/audit") Object audit() { a.requireAdmin(); return s.list("SELECT e.*,u.name AS actor FROM audit_events e LEFT JOIN users u ON u.id=e.actor_id ORDER BY e.id DESC LIMIT 500"); }
}
