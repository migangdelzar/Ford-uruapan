package com.ford.service.conversations.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds a {@link DealershipHoursPolicy} from configuration without inventing holiday dates. */
public final class DealershipHoursFactory {

    private DealershipHoursFactory() {
    }

    public static DealershipHoursPolicy create(Clock clock, ConversationProperties.Hours hours) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(hours);
        var zone = ZoneId.of(hours.zone());
        var weekday = new DealershipHours(hours.weekdayOpens(), hours.weekdayCloses());
        var saturday = new DealershipHours(hours.saturdayOpens(), hours.saturdayCloses());
        Map<DayOfWeek, DealershipHours> weekly = new EnumMap<>(DayOfWeek.class);
        for (var day : new DayOfWeek[] {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
            weekly.put(day, weekday);
        }
        weekly.put(DayOfWeek.SATURDAY, saturday);
        weekly.put(DayOfWeek.SUNDAY, DealershipHours.closed());
        Map<LocalDate, DealershipHours> overrides = new LinkedHashMap<>();
        for (var entry : hours.calendar().entrySet()) {
            overrides.put(LocalDate.parse(entry.getKey()), parseInterval(entry.getKey(), entry.getValue()));
        }
        return new DealershipHoursPolicy(clock, zone, weekly, overrides);
    }

    private static DealershipHours parseInterval(String date, String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("closed")) {
            return DealershipHours.closed();
        }
        var parts = value.split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid calendar hours for " + date + ": " + value);
        }
        return new DealershipHours(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]));
    }
}
