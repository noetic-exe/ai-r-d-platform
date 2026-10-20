package com.aird;

import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;

@Component
public class Access {
    final Store s;
    Access(Store s) { this.s=s; }
    long uid() { var a=SecurityContextHolder.getContext().getAuthentication(); Problem.require(a!=null && !a.getName().equals("anonymousUser"),401,"Please sign in."); return Long.parseLong(a.getName()); }
    Map<String,Object> user() { return s.one("SELECT * FROM users WHERE id=? AND active=TRUE",uid()); }
    boolean admin() { return "admin".equals(user().get("role")); }
    void requireAdmin() { Problem.require(admin(),403,"Administrator access is required."); }
    Map<String,Object> project(long id,boolean write) {
        var p=s.one("SELECT * FROM projects WHERE id=?",id);
        Problem.require(admin() || s.count("SELECT COUNT(*) FROM memberships WHERE project_id=? AND user_id=?",id,uid())>0,404,"Project not found.");
        Problem.require(!write || "active".equals(p.get("status")),409,"This project is archived.");
        return p;
    }
    Map<String,Object> version(long id,boolean write) {
        var v=s.one("SELECT v.*,d.project_id,d.name AS dataset_name,d.source,d.license FROM dataset_versions v JOIN datasets d ON d.id=v.dataset_id WHERE v.id=?",id);
        project(Store.number(v,"project_id"),write); return v;
    }
    Map<String,Object> job(long id) { var j=s.one("SELECT * FROM jobs WHERE id=?",id); project(Store.number(j,"project_id"),false); return j; }
    Map<String,Object> experiment(long id,boolean write) { var e=s.one("SELECT * FROM experiments WHERE id=?",id); project(Store.number(e,"project_id"),write); return e; }
}
