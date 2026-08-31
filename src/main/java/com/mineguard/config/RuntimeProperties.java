package com.mineguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mineguard.runtime")
public record RuntimeProperties(@DefaultValue("true") boolean schedulerEnabled,
                                @DefaultValue("30") int leaseSeconds,
                                @DefaultValue("600") int approvalSeconds,
                                @DefaultValue("3600") int sessionSeconds,
                                @DefaultValue("local") String nodeId,
                                @DefaultValue("") String bootstrapUsername,
                                @DefaultValue("") String bootstrapPassword) {
    public RuntimeProperties {
        if (leaseSeconds < 3 || approvalSeconds < 1 || sessionSeconds < 60) throw new IllegalArgumentException("运行期限配置无效");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("节点名称格式无效");
    }
    @Override public String toString() { return "RuntimeProperties[nodeId=" + nodeId + ", 凭据已隐藏]"; }
}
