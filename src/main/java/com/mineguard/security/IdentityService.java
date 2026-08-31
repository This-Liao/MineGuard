package com.mineguard.security;

import com.mineguard.config.RuntimeProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 随机 Bearer 会话仅存摘要；密码使用 BCrypt，角色及禁用状态在每次认证时重新读取。 */
@Service
public class IdentityService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final RuntimeProperties config;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwords, RuntimeProperties config, PlatformTransactionManager tm) {
        this.jdbc = jdbc; this.passwords = passwords; this.config = config; this.tx = new TransactionTemplate(tm);
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override public void run(ApplicationArguments args) {
        if (config.bootstrapUsername() != null && !config.bootstrapUsername().isBlank()) {
            validatePassword(config.bootstrapPassword());
            if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Long.class, config.bootstrapUsername()) == 0) {
                try { createAccount("default", config.bootstrapUsername(), config.bootstrapPassword(), Set.of(Role.ADMIN)); }
                catch (DuplicateKeyException ignored) { /* 双实例仅允许一个节点完成引导账号创建。 */ }
            }
        }
    }

    public LoginResponse login(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}") || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalidCredentials();
        String subject = Digests.sha256(username.toLowerCase(Locale.ROOT));
        try { jdbc.update("INSERT INTO auth_throttle(subject_hash,failures,updated_at) VALUES (?,0,CURRENT_TIMESTAMP)", subject); }
        catch (DuplicateKeyException ignored) { }
        LoginResponse response = tx.execute(status -> {
            var throttle = jdbc.queryForMap("SELECT failures,locked_until FROM auth_throttle WHERE subject_hash=? FOR UPDATE", subject);
            var lockedUntil = jdbc.queryForObject("SELECT locked_until FROM auth_throttle WHERE subject_hash=?", (rs, row) -> rs.getTimestamp(1), subject);
            Instant now = now();
            if (lockedUntil != null && lockedUntil.toInstant().isAfter(now)) return null;
            List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM app_user WHERE username=?", username);
            String hash = users.isEmpty() ? dummyHash : (String) users.getFirst().get("password_hash");
            boolean correct = passwords.matches(password, hash);
            if (users.isEmpty() || !correct || !Boolean.TRUE.equals(users.getFirst().get("enabled"))) {
                int failures = lockedUntil == null ? ((Number) throttle.get("failures")).intValue() + 1 : 1;
                jdbc.update("UPDATE auth_throttle SET failures=?,locked_until=?,updated_at=? WHERE subject_hash=?", failures,
                        failures >= 5 ? Timestamp.from(now.plusSeconds(300)) : null, Timestamp.from(now), subject);
                return null;
            }
            jdbc.update("UPDATE auth_throttle SET failures=0,locked_until=NULL,updated_at=? WHERE subject_hash=?", Timestamp.from(now), subject);
            Actor actor = actor(users.getFirst());
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Instant expires = now.plusSeconds(config.sessionSeconds());
            jdbc.update("INSERT INTO auth_session(token_hash,user_id,expires_at,created_at) VALUES (?,?,?,?)", Digests.sha256(token), actor.userId(), Timestamp.from(expires), Timestamp.from(now));
            audit(actor, "LOGIN", actor.userId());
            return new LoginResponse(token, "Bearer", expires, actor);
        });
        if (response == null) throw invalidCredentials();
        return response;
    }

    public Optional<Actor> authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.queryForList("SELECT u.* FROM auth_session s JOIN app_user u ON u.user_id=s.user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE", Digests.sha256(token))
                .stream().findFirst().map(this::actor);
    }
    public Optional<Actor> enabledUser(String id) {
        return jdbc.queryForList("SELECT * FROM app_user WHERE user_id=? AND enabled=TRUE", id).stream().findFirst().map(this::actor);
    }
    public void logout(String token) { if (token != null) jdbc.update("DELETE FROM auth_session WHERE token_hash=?", Digests.sha256(token)); }

    public Actor createAccount(String tenant, String username, String password, Set<Role> roles) {
        if (tenant == null || !tenant.matches("[A-Za-z0-9_.-]{1,64}") || username == null || !username.matches("[A-Za-z0-9_.-]{3,64}") || roles == null || roles.isEmpty()) throw new IllegalArgumentException("账号、租户或角色无效");
        validatePassword(password);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_user(user_id,tenant_id,username,password_hash,roles,enabled,created_at) VALUES (?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
                id, tenant, username, passwords.encode(password), String.join(",", roles.stream().map(Enum::name).sorted().toList()));
        return new Actor(id, tenant, username, roles);
    }
    public List<Actor> listAccounts(String tenant) {
        return jdbc.queryForList("SELECT * FROM app_user WHERE tenant_id=? ORDER BY username", tenant).stream().map(this::actor).toList();
    }
    public void setEnabled(Actor admin, String id, boolean enabled) {
        if (admin.userId().equals(id)) throw new IllegalArgumentException("不能通过此接口禁用自己");
        tx.executeWithoutResult(status -> {
            int count = jdbc.update("UPDATE app_user SET enabled=? WHERE user_id=? AND tenant_id=?", enabled, id, admin.tenantId());
            if (count != 1) throw new NoSuchElementException("用户不存在");
            if (!enabled) jdbc.update("DELETE FROM auth_session WHERE user_id=?", id);
            audit(admin, enabled ? "ENABLE_USER" : "DISABLE_USER", id);
        });
    }
    public void audit(Actor actor, String action, String resource) {
        jdbc.update("INSERT INTO security_audit(audit_id,tenant_id,actor_id,action,resource_id,occurred_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), actor.tenantId(), actor.userId(), action, resource);
    }
    private Actor actor(Map<String, Object> row) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String value : row.get("roles").toString().split(",")) roles.add(Role.valueOf(value));
        return new Actor(row.get("user_id").toString(), row.get("tenant_id").toString(), row.get("username").toString(), roles);
    }
    private Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, row) -> rs.getTimestamp(1).toInstant()); }
    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("密码至少 12 个字符且 UTF-8 编码不超过 72 字节");
    }
    private BadCredentialsException invalidCredentials() { return new BadCredentialsException("账号或凭据无效，请稍后重试"); }
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, Actor user) {
        @Override public String toString() { return "LoginResponse[凭据已隐藏]"; }
    }
}
