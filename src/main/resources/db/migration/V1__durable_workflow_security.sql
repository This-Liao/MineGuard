-- 任务快照、租约及事件在同一数据库内提交，兼容 H2 与 PostgreSQL。
CREATE TABLE agent_task (
    task_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    snapshot TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    event_sequence BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP WITH TIME ZONE,
    fence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, owner_id, request_key)
);
CREATE INDEX agent_task_queue_idx ON agent_task (state, lease_until, created_at);
CREATE INDEX agent_task_tenant_idx ON agent_task (tenant_id, owner_id, created_at);
CREATE TABLE task_event (
    task_id VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
    sequence BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (task_id, sequence)
);
CREATE TABLE task_step (
    task_id VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
    step_id VARCHAR(128) NOT NULL,
    operation_key VARCHAR(160) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    high_risk BOOLEAN NOT NULL,
    result TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (task_id, step_id)
);
CREATE TABLE task_decision (
    task_id VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
    actor_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (task_id, actor_id, request_key)
);
CREATE TABLE app_user (
    user_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    roles VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE auth_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(user_id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX auth_session_user_idx ON auth_session (user_id);
CREATE TABLE auth_throttle (
    subject_hash VARCHAR(64) PRIMARY KEY,
    failures INTEGER NOT NULL,
    locked_until TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE security_audit (
    audit_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
