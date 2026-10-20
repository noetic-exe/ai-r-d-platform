package com.aird;

import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class SessionRevoker {
    static final Map<String,HttpSession> sessions=new ConcurrentHashMap<>();
    @Bean ServletListenerRegistrationBean<HttpSessionListener> sessionListener() {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            public void sessionCreated(HttpSessionEvent e) { sessions.put(e.getSession().getId(),e.getSession()); }
            public void sessionDestroyed(HttpSessionEvent e) { sessions.values().remove(e.getSession()); }
        });
    }
    static void revoke(long id) {
        for(var session:List.copyOf(sessions.values())) try {
            var context=session.getAttribute("SPRING_SECURITY_CONTEXT");
            if(context instanceof SecurityContext c && c.getAuthentication()!=null && c.getAuthentication().getName().equals(String.valueOf(id))) session.invalidate();
        } catch(IllegalStateException ignored) { }
    }
}
