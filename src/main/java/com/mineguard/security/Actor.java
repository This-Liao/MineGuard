package com.mineguard.security;

import java.util.Set;

/** 身份来自后端会话，不接受请求正文提供的租户或角色。 */
public record Actor(String userId, String tenantId, String username, Set<Role> roles) {
    public Actor { roles = Set.copyOf(roles); }
    public boolean has(Role role) { return roles.contains(role); }
    public static Actor internal(String name) { return new Actor(name, "evaluation", name, Set.of(Role.OPERATOR, Role.APPROVER)); }
}
