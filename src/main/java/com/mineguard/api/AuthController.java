package com.mineguard.api;

import com.mineguard.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final IdentityService identities;
    private final TaskAccessPolicy policy;
    private final org.springframework.boot.availability.ApplicationAvailability availability;
    public AuthController(IdentityService identities, TaskAccessPolicy policy, org.springframework.boot.availability.ApplicationAvailability availability) {
        this.identities = identities; this.policy = policy; this.availability = availability;
    }
    @GetMapping("/health") public org.springframework.http.ResponseEntity<Map<String, String>> health() {
        // Tomcat 开始监听不等于引导账号等 ApplicationRunner 已完成；就绪探针必须覆盖这段窗口。
        boolean ready = availability.getReadinessState() == org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC;
        return org.springframework.http.ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "UP" : "STARTING"));
    }
    @PostMapping("/auth/login") public IdentityService.LoginResponse login(@Valid @RequestBody LoginRequest request) { return identities.login(request.username(), request.password()); }
    @GetMapping("/auth/me") public Actor me(@AuthenticationPrincipal Actor actor) { return actor; }
    @PostMapping("/auth/logout") public Map<String, Boolean> logout(@RequestHeader("Authorization") String header) {
        identities.logout(BearerTokenFilter.token(header)); return Map.of("loggedOut", true);
    }
    @GetMapping("/admin/users") public List<Actor> users(@AuthenticationPrincipal Actor actor) { policy.requireRole(actor, Role.ADMIN); return identities.listAccounts(actor.tenantId()); }
    @PostMapping("/admin/users") public Actor create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody UserRequest request) {
        policy.requireRole(actor, Role.ADMIN);
        Actor user = identities.createAccount(actor.tenantId(), request.username(), request.password(), request.roles());
        identities.audit(actor, "CREATE_USER", user.userId()); return user;
    }
    @PostMapping("/admin/users/{id}/enabled") public Map<String, Boolean> enabled(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody EnabledRequest request) {
        policy.requireRole(actor, Role.ADMIN); identities.setEnabled(actor, id, request.enabled()); return Map.of("enabled", request.enabled());
    }
    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[凭据已隐藏]"; }
    }
    public record UserRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password, Set<Role> roles) {
        @Override public String toString() { return "UserRequest[凭据已隐藏]"; }
    }
    public record EnabledRequest(boolean enabled) {}
}
