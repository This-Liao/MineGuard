package com.mineguard.config;

import com.mineguard.event.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "mineguard.demo-data-enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder {
    public static final long SEED = 20260831L;
    public static final int EVENT_COUNT = 420;
    public static final Instant EVENT_ANCHOR = Instant.parse("2026-08-31T00:00:00Z");

    private final SafetyEventRepository repository;

    public DemoDataSeeder(SafetyEventRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seed() {
        // 演示初始化不能因生产数据量不同而覆盖已有记录。
        if (repository.count() > 0) return;
        Random random = new Random(SEED);
        String[] areas = {"1号采区", "2号采区", "3号采区", "主运输巷", "通风机房"};
        EventType[] types = EventType.values();
        Severity[] severities = Severity.values();
        List<SafetyEvent> events = new ArrayList<>(EVENT_COUNT);
        for (int i = 1; i <= EVENT_COUNT; i++) {
            EventType type = types[random.nextInt(types.length)];
            Severity severity = severities[random.nextInt(severities.length)];
            String area = areas[random.nextInt(areas.length)];
            String device = "camera-%02d".formatted(1 + random.nextInt(24));
            Instant timestamp = EVENT_ANCHOR.minus(random.nextInt(30 * 24 * 60), ChronoUnit.MINUTES);
            events.add(new SafetyEvent(
                    "EVT-%04d".formatted(i), area, device, type, severity, timestamp,
                    area + "检测到" + type.name() + "，严重等级" + severity.name(),
                    random.nextBoolean() ? "OPEN" : "RESOLVED"));
        }
        repository.replaceAll(events);
    }
}
