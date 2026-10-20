package com.aird;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class AuthController {
    final Store s; final Access access; final PasswordEncoder passwords; final HttpSessionSecurityContextRepository contexts;
    final ConcurrentHashMap<String,long[]> failures=new ConcurrentHashMap<>();
    @Value("${app.demo}") boolean demo;
    AuthController(Store s,Access access,PasswordEncoder passwords,HttpSessionSecurityContextRepository contexts) { this.s=s; this.access=access; this.passwords=passwords; this.contexts=contexts; }
    record Login(@NotBlank @Email @Size(max=200) String email,@NotBlank @Size(max=200) String password) {}
    record Profile(@NotBlank @Size(max=100) String name,@NotBlank @Email @Size(max=200) String email,@Size(max=200) String current_password,@Size(min=12,max=200) String password) {}
    @GetMapping("/config") Object config() { return Map.of("demo",demo,"name","AI R&D Platform"); }
    @GetMapping("/auth/csrf") Object csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @PostMapping("/auth/login") synchronized Object login(@Valid @RequestBody Login input,HttpServletRequest req,HttpServletResponse res) {
        String key=req.getRemoteAddr(); long now=System.currentTimeMillis();
        failures.entrySet().removeIf(e->now-e.getValue()[1]>900000);
        var attempts=failures.get(key);
        Problem.require(attempts==null || attempts[0]<10,429,"Too many login attempts. Try again in 15 minutes.");
        var rows=s.list("SELECT * FROM users WHERE email=? AND active=TRUE",input.email().trim().toLowerCase(Locale.ROOT));
        if(rows.isEmpty() || !passwords.matches(input.password(),rows.getFirst().get("password_hash").toString())) {
            failures.compute(key,(k,v)->new long[]{v==null?1:v[0]+1,now});
            throw new Problem(401,"Email or password is incorrect.");
        }
        failures.remove(key); var user=rows.getFirst();
        req.getSession(); req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(s.id(user)),null,List.of(new SimpleGrantedAuthority("ROLE_"+user.get("role")))));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);
        return s.publicUser(user);
    }
    @GetMapping("/auth/me") Object me() { return s.publicUser(access.user()); }
    @PostMapping("/auth/logout") Object logout(HttpServletRequest req) { if(req.getSession(false)!=null) req.getSession().invalidate(); SecurityContextHolder.clearContext(); return Map.of("message","Signed out."); }
    @PatchMapping("/profile") Object profile(@Valid @RequestBody Profile input,HttpServletRequest req) {
        var user=access.user(); long id=s.id(user);
        boolean sensitive=!input.email().trim().equalsIgnoreCase(user.get("email").toString()) || input.password()!=null;
        Problem.require(!sensitive || (input.current_password()!=null && passwords.matches(input.current_password(),user.get("password_hash").toString())),403,"Enter your current password to change your email or password.");
        Problem.require(!Boolean.TRUE.equals(user.get("must_change_password")) || input.password()!=null,422,"Choose a new password of at least 12 characters.");
        s.db.update("UPDATE users SET name=?,email=?,password_hash=?,must_change_password=? WHERE id=?",input.name().trim(),input.email().trim().toLowerCase(Locale.ROOT),input.password()==null?user.get("password_hash"):passwords.encode(input.password()),input.password()==null?user.get("must_change_password"):false,id);
        s.audit(id,"Updated profile","user",id);
        // Invalidating the current session also removes all prior sessions via the registry below.
        if(sensitive) { SessionRevoker.revoke(id); if(req.getSession(false)!=null) req.getSession().invalidate(); SecurityContextHolder.clearContext(); }
        return Map.of("message",sensitive?"Profile updated. Please sign in again.":"Profile updated.","reauthenticate",sensitive);
    }
}
