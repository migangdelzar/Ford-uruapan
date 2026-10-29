package com.ford.service.conversations.application;

import com.ford.service.conversations.api.AfterHoursDigestWindow;
import com.ford.service.conversations.api.AfterHoursDigestWindowProvider;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Evaluates the configured local calendar without assuming holiday dates. */
public final class DealershipHoursPolicy implements AfterHoursDigestWindowProvider {
    private static final LocalTime DIGEST_AVAILABLE_AT = LocalTime.of(8, 45);
    private static final int SEARCH_HORIZON_DAYS = 14;

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

    @Override
    public boolean isOutsideBusinessHours(Instant instant) {
        return !at(instant).open();
    }

    public Status at(Instant instant) {
        var local = Objects.requireNonNull(instant).atZone(zone);
        var today = hours(local.toLocalDate());
        if (!today.isClosed() && !local.toLocalTime().isBefore(today.opens())
                && local.toLocalTime().isBefore(today.closes())) {
            return new Status(true, instant, holidayCalendarConfigured());
        }
        for (var offset = 0; offset <= SEARCH_HORIZON_DAYS; offset++) {
            var date = local.toLocalDate().plusDays(offset);
            var interval = hours(date);
            if (interval.isClosed()) continue;
            var opening = date.atTime(interval.opens()).atZone(zone).toInstant();
            if (opening.isAfter(instant)) return new Status(false, opening, holidayCalendarConfigured());
        }
        throw new IllegalStateException("No dealership opening in the next 14 days");
    }

    @Override
    public AfterHoursDigestWindow getAfterHoursDigestWindow() {
        var now = clock.instant();
        var localNow = now.atZone(zone);
        var businessDate = digestDate(localNow.toLocalDate(), localNow.toLocalTime());
        var businessHours = hours(businessDate);
        var availableAt = businessDate.atTime(DIGEST_AVAILABLE_AT).atZone(zone).toInstant();
        var opensAt = businessDate.atTime(businessHours.opens()).atZone(zone).toInstant();
        var previousOpenDate = previousOpenDate(businessDate);
        var previousHours = hours(previousOpenDate);
        var windowStart = previousOpenDate.atTime(previousHours.closes()).atZone(zone).toInstant();
        var windowEnd = now.isBefore(availableAt) ? availableAt
                : now.isBefore(opensAt) ? now : opensAt;
        return new AfterHoursDigestWindow(businessDate, zone.getId(), availableAt,
                windowStart, windowEnd, !now.isBefore(availableAt));
    }

    private LocalDate digestDate(LocalDate today, java.time.LocalTime localTime) {
        for (var offset = 0; offset <= SEARCH_HORIZON_DAYS; offset++) {
            var date = today.plusDays(offset);
            var interval = hours(date);
            if (interval.isClosed()) continue;
            if (offset > 0 || localTime.isBefore(interval.closes())) return date;
        }
        throw new IllegalStateException("No dealership opening in the next 14 days");
    }

    private LocalDate previousOpenDate(LocalDate businessDate) {
        for (var offset = 1; offset <= SEARCH_HORIZON_DAYS; offset++) {
            var date = businessDate.minusDays(offset);
            if (!hours(date).isClosed()) return date;
        }
        throw new IllegalStateException("No previous dealership opening in the last 14 days");
    }

    private DealershipHours hours(LocalDate date) {
        return overrides.getOrDefault(date, weekly.getOrDefault(date.getDayOfWeek(), DealershipHours.closed()));
    }

    public record Status(boolean open, Instant nextOpening, boolean holidayCalendarConfigured) { }

}
