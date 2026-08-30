package com.mineguard.event;

import com.mineguard.config.DemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SafetyEventRepositoryIntegrationTest {
    @Autowired SafetyEventRepository repository;

    @Test
    void seedsFixedEventCount() {
        assertThat(repository.count()).isEqualTo(420);
    }

    @Test
    void filtersAndAggregatesStructuredEvents() {
        SafetyEventFilter filter = new SafetyEventFilter("3号采区", EventType.GAS_WARNING,
                DemoDataSeeder.EVENT_ANCHOR.minus(7, ChronoUnit.DAYS), DemoDataSeeder.EVENT_ANCHOR, null);
        var events = repository.find(filter);
        assertThat(events).isNotEmpty().allMatch(event -> event.area().equals("3号采区") && event.eventType() == EventType.GAS_WARNING);
        assertThat(repository.aggregate(filter).get("GAS_WARNING")).isEqualTo((long) events.size());
    }
}
