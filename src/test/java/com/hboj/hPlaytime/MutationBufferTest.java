package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MutationBufferTest {
    @Test void anUncertainAdditionIsRetriedWithTheSameIdBeforeAQueuedReset() {
        UUID uuid = UUID.randomUUID(); MutationBuffer buffer = new MutationBuffer();
        buffer.add(uuid, TestData.add(uuid, 0, 100).changes().get(uuid));
        var first = buffer.capture(); buffer.reset(uuid, 100);
        buffer.add(uuid, TestData.add(uuid, 100, 200).changes().get(uuid));
        assertEquals(first, buffer.capture());
        assertEquals(100, buffer.project(StorageView.empty()).players().get(uuid).alltime());
        buffer.acknowledge(first, Set.of(uuid));
        var next = buffer.capture();
        assertEquals(List.of(StorageMutation.Kind.RESET_PLAYER, StorageMutation.Kind.ADD), next.stream().map(StorageMutation::kind).toList());
        StorageView committed = TestData.apply(StorageView.empty(), first);
        committed = TestData.apply(committed, next);
        assertEquals(100, committed.players().get(uuid).alltime());
    }

    @Test void shutdownIncludesInFlightAndQueuedMutationsAndCoalescesContiguousIntervals() {
        UUID uuid = UUID.randomUUID(); MutationBuffer buffer = new MutationBuffer();
        for (int i = 0; i < 1000; i++) buffer.add(uuid, TestData.add(uuid, i, i + 1).changes().get(uuid));
        var first = buffer.capture(); assertEquals(1, first.get(0).changes().get(uuid).slices().size());
        buffer.reset(uuid, 1000); buffer.add(uuid, TestData.add(uuid, 1000, 1100).changes().get(uuid));
        var finalOperations = buffer.captureShutdown();
        assertEquals(first.get(0).id(), finalOperations.get(0).id()); assertEquals(3, finalOperations.size());
        assertEquals(100, TestData.apply(StorageView.empty(), finalOperations).players().get(uuid).alltime());
    }
}
