package com.aird;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
public class Security {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noDefaultAccount() { return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Use session login."); }; }
    @Bean PasswordEncoder passwords() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean HttpSessionSecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain chain(HttpSecurity http, Store store, HttpSessionSecurityContextRepository repository) throws Exception {
        http.csrf(csrf->csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(c->c.securityContextRepository(repository))
            .authorizeHttpRequests(a->a.requestMatchers("/api/auth/csrf","/api/auth/login","/api/health","/api/config").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .requestCache(c->c.disable())
            .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->reply(res,401,"Please sign in to continue."))
                .accessDeniedHandler((req,res,ex)->reply(res,403,"Access denied or security token expired. Refresh and try again.")))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser")) {
                        var users=store.list("SELECT * FROM users WHERE id=? AND active=TRUE",Long.parseLong(auth.getName()));
                        if(users.isEmpty()) { if(req.getSession(false)!=null) req.getSession().invalidate(); SecurityContextHolder.clearContext(); reply(res,401,"This account is no longer active."); return; }
                        var user=users.getFirst();
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(auth.getName(),null,List.of(new SimpleGrantedAuthority("ROLE_"+user.get("role")))));
                        if(Boolean.TRUE.equals(user.get("must_change_password")) && req.getRequestURI().startsWith("/api/") && !Set.of("/api/auth/me","/api/auth/csrf","/api/auth/logout","/api/profile").contains(req.getRequestURI())) {
                            reply(res,403,"Change your temporary password in Profile before continuing."); return;
                        }
                    }
                    chain.doFilter(req,res);
                }
            },AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static void reply(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json"); res.getWriter().write("{\"error\":{\"message\":\""+message+"\"}}");
    }
}
