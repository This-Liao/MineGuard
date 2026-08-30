package com.mineguard.event;

import java.util.List;
import java.util.Map;

public interface SafetyEventRepository {
    void replaceAll(List<SafetyEvent> events);
    List<SafetyEvent> find(SafetyEventFilter filter);
    Map<String, Long> aggregate(SafetyEventFilter filter);
    long count();
}
