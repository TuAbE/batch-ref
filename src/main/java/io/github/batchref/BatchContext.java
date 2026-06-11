package io.github.batchref;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BatchContext {

    private final List<BatchRegistration<?>> pendingRegistrations = new ArrayList<>();
    private boolean flushing;

    public <T> void register(BatchQuery<T> query, BatchRef<T> ref) {
        pendingRegistrations.add(new BatchRegistration<>(query, ref));
    }

    public void flush() {
        if (flushing) {
            return;
        }
        flushing = true;
        try {
            while (!pendingRegistrations.isEmpty()) {
                flushPendingRegistrations();
            }
        } finally {
            flushing = false;
        }
    }

    public boolean hasPendingRefs() {
        return !pendingRegistrations.isEmpty();
    }

    private void flushPendingRegistrations() {
        List<BatchRegistration<?>> registrations = new ArrayList<>(pendingRegistrations);
        pendingRegistrations.clear();
        try {
            Map<String, BatchGroup> groups = new LinkedHashMap<>();
            for (BatchRegistration<?> registration : registrations) {
                groups.computeIfAbsent(
                        registration.query().loaderName(),
                        loaderName -> new BatchGroup(registration.query())
                ).add(registration);
            }
            for (BatchGroup group : groups.values()) {
                group.loadAndReplay();
            }
        } catch (RuntimeException ex) {
            pendingRegistrations.addAll(0, registrations);
            throw ex;
        }
    }
}
