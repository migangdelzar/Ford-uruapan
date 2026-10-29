package com.ford.service.conversations.application;

import java.time.LocalTime;

/** A local opening interval; equal endpoints represent a closed day. */
public record DealershipHours(LocalTime opens, LocalTime closes) {
    public DealershipHours {
        if (opens == null || closes == null || closes.isBefore(opens)) {
            throw new IllegalArgumentException("Dealership hours must be an ordered local interval");
        }
    }

    public static DealershipHours closed() {
        return new DealershipHours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
    }

    public boolean isClosed() {
        return opens.equals(closes);
    }
}
