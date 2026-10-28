package com.hboj.hPlaytime;

/** Eligible wall-clock interval, already split at the configured day boundary. */
public record TimeSlice(long from, long to, String day, String month) {
    public TimeSlice {
        if (to < from) throw new IllegalArgumentException("Negative playtime interval");
    }
    public long millisAfter(long cutoff) { return Math.max(0, to - Math.max(from, cutoff)); }
}
