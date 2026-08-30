package com.mineguard.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcSafetyEventRepository implements SafetyEventRepository {
    private final JdbcTemplate jdbc;

    public JdbcSafetyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS safety_event (
                  event_id VARCHAR(64) PRIMARY KEY,
                  area VARCHAR(64) NOT NULL,
                  device_id VARCHAR(64) NOT NULL,
                  event_type VARCHAR(64) NOT NULL,
                  severity VARCHAR(32) NOT NULL,
                  event_time TIMESTAMP WITH TIME ZONE NOT NULL,
                  description VARCHAR(512) NOT NULL,
                  status VARCHAR(32) NOT NULL
                )
                """);
    }

    @Override
    public void replaceAll(List<SafetyEvent> events) {
        jdbc.update("DELETE FROM safety_event");
        jdbc.batchUpdate("INSERT INTO safety_event(event_id, area, device_id, event_type, severity, event_time, description, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                events,
                100,
                (ps, event) -> {
                    ps.setString(1, event.eventId());
                    ps.setString(2, event.area());
                    ps.setString(3, event.deviceId());
                    ps.setString(4, event.eventType().name());
                    ps.setString(5, event.severity().name());
                    ps.setTimestamp(6, Timestamp.from(event.timestamp()));
                    ps.setString(7, event.description());
                    ps.setString(8, event.status());
                });
    }

    @Override
    public List<SafetyEvent> find(SafetyEventFilter filter) {
        Query query = buildQuery(filter, "SELECT * FROM safety_event");
        query.sql.append(" ORDER BY event_time DESC");
        return jdbc.query(query.sql.toString(), (rs, row) -> new SafetyEvent(
                rs.getString("event_id"),
                rs.getString("area"),
                rs.getString("device_id"),
                EventType.valueOf(rs.getString("event_type")),
                Severity.valueOf(rs.getString("severity")),
                rs.getTimestamp("event_time").toInstant(),
                rs.getString("description"),
                rs.getString("status")
        ), query.args.toArray());
    }

    @Override
    public Map<String, Long> aggregate(SafetyEventFilter filter) {
        Query query = buildQuery(filter, "SELECT event_type, COUNT(*) AS total FROM safety_event");
        query.sql.append(" GROUP BY event_type ORDER BY total DESC");
        return jdbc.query(query.sql.toString(), rs -> {
            Map<String, Long> values = new java.util.LinkedHashMap<>();
            while (rs.next()) values.put(rs.getString("event_type"), rs.getLong("total"));
            return values;
        }, query.args.toArray());
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM safety_event", Long.class);
        return count == null ? 0 : count;
    }

    private Query buildQuery(SafetyEventFilter filter, String base) {
        StringBuilder sql = new StringBuilder(base).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (filter != null && filter.area() != null && !filter.area().isBlank()) {
            sql.append(" AND area = ?"); args.add(filter.area());
        }
        if (filter != null && filter.eventType() != null) {
            sql.append(" AND event_type = ?"); args.add(filter.eventType().name());
        }
        if (filter != null && filter.startTime() != null) {
            sql.append(" AND event_time >= ?"); args.add(Timestamp.from(filter.startTime()));
        }
        if (filter != null && filter.endTime() != null) {
            sql.append(" AND event_time <= ?"); args.add(Timestamp.from(filter.endTime()));
        }
        if (filter != null && filter.severity() != null) {
            sql.append(" AND severity = ?"); args.add(filter.severity().name());
        }
        return new Query(sql, args);
    }

    private record Query(StringBuilder sql, List<Object> args) {}
}
