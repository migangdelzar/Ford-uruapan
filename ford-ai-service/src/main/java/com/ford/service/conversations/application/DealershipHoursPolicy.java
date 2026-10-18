package com.ford.service.conversations.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Evaluates the configured local calendar without assuming holiday dates. */
public final class DealershipHoursPolicy {
    private final Clock clock;
    private final ZoneId zone;
    private final Map<DayOfWeek, DealershipHours> weekly;
    private final Map<LocalDate, DealershipHours> overrides;

    public DealershipHoursPolicy(Clock clock, ZoneId zone, Map<DayOfWeek, DealershipHours> weekly,
                                 Map<LocalDate, DealershipHours> overrides) {
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        this.weekly = Map.copyOf(Objects.requireNonNull(weekly));
        this.overrides = Map.copyOf(Objects.requireNonNull(overrides));
    }

    public boolean holidayCalendarConfigured() {
        return !overrides.isEmpty();
    }

    public Status current() {
        return at(clock.instant());
    }

    public Status at(Instant instant) {
        var local = Objects.requireNonNull(instant).atZone(zone);
        var today = hours(local.toLocalDate());
        if (!today.isClosed() && !local.toLocalTime().isBefore(today.opens())
                && local.toLocalTime().isBefore(today.closes())) {
            return new Status(true, instant, holidayCalendarConfigured());
        }
        for (var offset = 0; offset <= 14; offset++) {
            var date = local.toLocalDate().plusDays(offset);
            var interval = hours(date);
            if (interval.isClosed()) continue;
            var opening = date.atTime(interval.opens()).atZone(zone).toInstant();
            if (opening.isAfter(instant)) return new Status(false, opening, holidayCalendarConfigured());
        }
        throw new IllegalStateException("No dealership opening in the next 14 days");
    }

    private DealershipHours hours(LocalDate date) {
        return overrides.getOrDefault(date, weekly.getOrDefault(date.getDayOfWeek(), DealershipHours.closed()));
    }

    public record Status(boolean open, Instant nextOpening, boolean holidayCalendarConfigured) { }
}
