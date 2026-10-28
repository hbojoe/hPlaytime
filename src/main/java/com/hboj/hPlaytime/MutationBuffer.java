package com.hboj.hPlaytime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread outbox. An uncertain in-flight operation is never replaced by a newer snapshot. */
final class MutationBuffer {
    private final Map<UUID, StorageMutation.Change> changes = new HashMap<>();
    private final List<StorageMutation> queued = new ArrayList<>();
    private List<StorageMutation> inFlight = List.of();

    void add(UUID uuid, StorageMutation.Change change) { changes.merge(uuid, change, StorageMutation.Change::merge); }
    void reset(UUID uuid, long at) { seal(); queued.add(StorageMutation.reset(uuid, at)); }
    private void seal() { if (!changes.isEmpty()) { queued.add(StorageMutation.add(changes)); changes.clear(); } }

    List<StorageMutation> capture() {
        if (!inFlight.isEmpty()) return inFlight;
        seal(); inFlight = List.copyOf(queued); queued.clear();
        return inFlight;
    }
    List<StorageMutation> captureShutdown() {
        seal(); List<StorageMutation> result = new ArrayList<>(inFlight); result.addAll(queued);
        return List.copyOf(result);
    }
    List<StorageMutation> recoverySnapshot() {
        List<StorageMutation> all = new ArrayList<>(inFlight); all.addAll(queued);
        // Not yet submitted to SQL: a recovery-only snapshot can get a fresh ID without growing the outbox.
        if (!changes.isEmpty()) all.add(StorageMutation.add(changes));
        return List.copyOf(all);
    }
    Set<UUID> acknowledge(List<StorageMutation> saved, Set<UUID> population) {
        if (!saved.equals(inFlight)) throw new IllegalStateException("Out-of-order persistence acknowledgement");
        Set<UUID> affected = affected(saved, population);
        inFlight = List.of(); return affected;
    }
    List<StorageMutation> outstanding() {
        List<StorageMutation> all = new ArrayList<>(inFlight); all.addAll(queued);
        if (!changes.isEmpty()) all.add(new StorageMutation(new UUID(0, 0), StorageMutation.Kind.ADD, changes, null, 0));
        return all;
    }
    boolean dirty() { return !changes.isEmpty() || !queued.isEmpty() || !inFlight.isEmpty(); }
    boolean uncertain() { return !inFlight.isEmpty(); }
    StorageView project(StorageView base) {
        StorageView result = base;
        for (StorageMutation mutation : outstanding()) result = mutation.project(result);
        return result;
    }
    Set<UUID> affected(Set<UUID> population) { return affected(outstanding(), population); }
    private Set<UUID> affected(List<StorageMutation> mutations, Set<UUID> population) {
        Set<UUID> affected = new HashSet<>();
        for (StorageMutation mutation : mutations) {
            if (mutation.kind() == StorageMutation.Kind.RESET_ALL) affected.addAll(population);
            else if (mutation.kind() == StorageMutation.Kind.RESET_PLAYER) affected.add(mutation.target());
            else affected.addAll(mutation.changes().keySet());
        }
        return affected;
    }
}
