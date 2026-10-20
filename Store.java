package com.aird;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

@Component
@DependsOn("migrations")
public class Store {
    final JdbcTemplate db;
    final JsonMapper json = JsonMapper.builder().build();
    Store(JdbcTemplate db) { this.db=db; }
    public static String now() { return Instant.now().toString(); }
    List<Map<String,Object>> list(String sql, Object... args) { return db.queryForList(sql,args); }
    Map<String,Object> one(String sql, Object... args) { var rows=list(sql,args); if(rows.isEmpty()) throw new Problem(404,"Record not found."); return rows.getFirst(); }
    long count(String sql, Object... args) { return Objects.requireNonNull(db.queryForObject(sql,Long.class,args)); }
    long insert(String sql, Object... args) {
        var holder=new GeneratedKeyHolder();
        db.update(conn->{var ps=conn.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); return ps;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    String encode(Object o) { return json.writeValueAsString(o); }
    @SuppressWarnings("unchecked") Map<String,Object> decode(Object o) { return o==null?new LinkedHashMap<>():json.readValue(o.toString(),Map.class); }
    long id(Map<String,Object> row) { return ((Number)row.get("id")).longValue(); }
    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    void audit(long actor,String action,String kind,long id) { db.update("INSERT INTO audit_events(actor_id,action,record_type,record_id,created_at) VALUES(?,?,?,?,?)",actor,action,kind,id,now()); }
    void event(long job,String stage,String message) { db.update("INSERT INTO job_events(job_id,stage,message,created_at) VALUES(?,?,?,?)",job,stage,message,now()); }
    Map<String,Object> publicUser(Map<String,Object> user) { var copy=new LinkedHashMap<>(user); copy.remove("password_hash"); return copy; }
}
