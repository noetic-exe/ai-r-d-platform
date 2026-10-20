package com.aird;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class CollaborationController {
    final Store s; final Access a;
    CollaborationController(Store s,Access a) { this.s=s;this.a=a; }
    record Comment(@NotBlank @Size(max=2000) String body) {}
    @GetMapping("/projects/{id}/comments") Object comments(@PathVariable long id) { a.project(id,false); return s.list("SELECT c.*,u.name AS author FROM comments c JOIN users u ON u.id=c.user_id WHERE c.project_id=? AND c.experiment_id IS NULL ORDER BY c.id DESC LIMIT 200",id); }
    @PostMapping("/projects/{id}/comments") Object comment(@PathVariable long id,@Valid @RequestBody Comment in) { a.project(id,true); long cid=s.insert("INSERT INTO comments(project_id,user_id,body,created_at) VALUES(?,?,?,?)",id,a.uid(),in.body().trim(),Store.now()); return Map.of("id",cid,"message","Comment posted."); }
    @GetMapping("/experiments/{id}/comments") Object experimentComments(@PathVariable long id) { a.experiment(id,false); return s.list("SELECT c.*,u.name AS author FROM comments c JOIN users u ON u.id=c.user_id WHERE c.experiment_id=? ORDER BY c.id DESC LIMIT 200",id); }
    @PostMapping("/experiments/{id}/comments") Object experimentComment(@PathVariable long id,@Valid @RequestBody Comment in) { var e=a.experiment(id,true); long cid=s.insert("INSERT INTO comments(project_id,experiment_id,user_id,body,created_at) VALUES(?,?,?,?,?)",e.get("project_id"),id,a.uid(),in.body().trim(),Store.now()); return Map.of("id",cid); }
    @PostMapping("/projects/{id}/collaboration-requests") Object request(@PathVariable long id,@Valid @RequestBody AdminController.MemberInput in) {
        a.project(id,true); s.one("SELECT id FROM users WHERE id=? AND active=TRUE AND role='researcher'",in.user_id());
        Problem.require(s.count("SELECT COUNT(*) FROM memberships WHERE project_id=? AND user_id=?",id,in.user_id())==0,409,"This researcher is already a member.");
        Problem.require(s.count("SELECT COUNT(*) FROM collaboration_requests WHERE project_id=? AND user_id=? AND status='pending'",id,in.user_id())==0,409,"A request is already pending.");
        long rid=s.insert("INSERT INTO collaboration_requests(project_id,requester_id,user_id,created_at) VALUES(?,?,?,?)",id,a.uid(),in.user_id(),Store.now()); return Map.of("id",rid,"message","Request sent for administrator review.");
    }
    @GetMapping("/collaboration-requests") Object requests() {
        String base="SELECT r.*,p.name AS project_name,u.name AS researcher,q.name AS requester FROM collaboration_requests r JOIN projects p ON p.id=r.project_id JOIN users u ON u.id=r.user_id JOIN users q ON q.id=r.requester_id";
        return a.admin()?s.list(base+" ORDER BY r.id DESC LIMIT 200"):s.list(base+" WHERE r.requester_id=? OR r.user_id=? ORDER BY r.id DESC LIMIT 200",a.uid(),a.uid());
    }
    @PostMapping("/collaboration-requests/{id}/{decision}") @Transactional Object decide(@PathVariable long id,@PathVariable String decision) {
        a.requireAdmin(); Problem.require(Set.of("approve","reject").contains(decision),404,"Action not found.");
        var r=s.one("SELECT * FROM collaboration_requests WHERE id=? FOR UPDATE",id); Problem.require("pending".equals(r.get("status")),409,"This request has already been reviewed.");
        a.project(Store.number(r,"project_id"),true);
        if(decision.equals("approve")) {
            s.one("SELECT id FROM users WHERE id=? AND active=TRUE",r.get("user_id"));
            if(s.count("SELECT COUNT(*) FROM memberships WHERE project_id=? AND user_id=?",r.get("project_id"),r.get("user_id"))==0) s.db.update("INSERT INTO memberships(project_id,user_id) VALUES(?,?)",r.get("project_id"),r.get("user_id"));
        }
        s.db.update("UPDATE collaboration_requests SET status=? WHERE id=?",decision.equals("approve")?"approved":"rejected",id);
        s.audit(a.uid(),decision+" collaboration request","request",id); return Map.of("message","Request "+(decision.equals("approve")?"approved.":"rejected."));
    }
}
