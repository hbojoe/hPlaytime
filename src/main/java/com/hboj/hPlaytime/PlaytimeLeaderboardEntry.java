package com.hboj.hPlaytime;

import java.util.UUID;

public record PlaytimeLeaderboardEntry(UUID uuid, String playerName, long millis) {
}
