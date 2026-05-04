package com.hboj.hPlaytime;

import java.util.UUID;

public record PlaytimeSnapshot(
    UUID playerUuid,
    String playerName,
    long todayMillis,
    long monthMillis,
    long alltimeMillis,
    long lastSeenMillis
) {
}
