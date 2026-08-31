package com.mineguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class BearerTokenFilter extends OncePerRequestFilter {
    private final IdentityService identities;
    public BearerTokenFilter(IdentityService identities) { this.identities = identities; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = token(request.getHeader("Authorization"));
        if (token != null) identities.authenticate(token).ifPresent(actor -> {
            var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, authorities));
        });
        chain.doFilter(request, response);
    }
    public static String token(String header) { return header != null && header.startsWith("Bearer ") ? header.substring(7) : null; }
}
