package com.ford.service.conversations.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DealershipHoursPolicyTest {
    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

    @Test
    void weekdayBoundariesUseDealershipTimezone() {
        var opening = Instant.parse("2026-10-19T15:00:00Z");
        var closing = Instant.parse("2026-10-20T01:00:00Z");
        var policy = policy(opening, Map.of());

        assertThat(policy.current().open()).isTrue();
        assertThat(policy.at(opening.minusNanos(1)).open()).isFalse();
        assertThat(policy.at(closing.minusNanos(1)).open()).isTrue();
        assertThat(policy.at(closing).open()).isFalse();
        assertThat(policy.at(closing).nextOpening()).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"));
    }

    @Test
    void saturdayClosesAtTwoAndSundayOpensNextMonday() {
        var policy = policy(Instant.parse("2026-10-17T20:00:00Z"), Map.of());

        assertThat(policy.current().open()).isFalse();
        assertThat(policy.current().nextOpening()).isEqualTo(Instant.parse("2026-10-19T15:00:00Z"));
        assertThat(policy.at(Instant.parse("2026-10-17T19:59:59Z")).open()).isTrue();
        assertThat(policy.at(Instant.parse("2026-10-18T18:00:00Z")).open()).isFalse();
    }

    @Test
    void explicitHolidayClosureAndSpecialHoursOverrideWeeklySchedule() {
        var policy = policy(Instant.parse("2026-10-19T16:00:00Z"), Map.of(
                LocalDate.parse("2026-10-19"), DealershipHours.closed(),
                LocalDate.parse("2026-10-20"), new DealershipHours(LocalTime.NOON, LocalTime.of(16, 0))));

        assertThat(policy.current().open()).isFalse();
        assertThat(policy.current().nextOpening()).isEqualTo(Instant.parse("2026-10-20T18:00:00Z"));
        assertThat(policy.at(Instant.parse("2026-10-20T18:00:00Z")).open()).isTrue();
        assertThat(policy.at(Instant.parse("2026-10-20T22:00:00Z")).open()).isFalse();
    }

    @Test
    void absentHolidayCalendarRemainsExplicitlyUnresolved() {
        assertThat(policy(Instant.parse("2026-10-19T16:00:00Z"), Map.of()).holidayCalendarConfigured())
                .isFalse();
    }

    @Test
    void weekdayDigestIsHiddenUntilEightFortyFiveAndStartsAtPreviousSaturdayClose() {
        var beforeAvailability = policy(Instant.parse("2026-10-19T14:44:00Z"), Map.of())
                .getAfterHoursDigestWindow();

        assertThat(beforeAvailability.businessDate()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(beforeAvailability.timeZone()).isEqualTo("America/Mexico_City");
        assertThat(beforeAvailability.available()).isFalse();
        assertThat(beforeAvailability.availableAt()).isEqualTo(Instant.parse("2026-10-19T14:45:00Z"));
        assertThat(beforeAvailability.windowStart()).isEqualTo(Instant.parse("2026-10-17T20:00:00Z"));
        assertThat(beforeAvailability.windowEnd()).isEqualTo(Instant.parse("2026-10-19T14:45:00Z"));

        var atAvailability = policy(Instant.parse("2026-10-19T14:45:00Z"), Map.of())
                .getAfterHoursDigestWindow();
        assertThat(atAvailability.available()).isTrue();
        assertThat(atAvailability.windowEnd()).isEqualTo(Instant.parse("2026-10-19T14:45:00Z"));
    }

    @Test
    void digestWindowIncludesPreopenArrivalsButStopsAtOpening() {
        var beforeOpen = policy(Instant.parse("2026-10-19T14:50:00Z"), Map.of())
                .getAfterHoursDigestWindow();
        var afterOpen = policy(Instant.parse("2026-10-19T16:00:00Z"), Map.of())
                .getAfterHoursDigestWindow();

        assertThat(beforeOpen.windowEnd()).isEqualTo(Instant.parse("2026-10-19T14:50:00Z"));
        assertThat(afterOpen.windowEnd()).isEqualTo(Instant.parse("2026-10-19T15:00:00Z"));
    }

    @Test
    void closedOverrideMovesDigestToNextOpeningAndUsesPriorOpenClose() {
        var policy = policy(Instant.parse("2026-10-19T14:00:00Z"), Map.of(
                LocalDate.parse("2026-10-19"), DealershipHours.closed(),
                LocalDate.parse("2026-10-20"), new DealershipHours(LocalTime.NOON, LocalTime.of(16, 0))));

        var window = policy.getAfterHoursDigestWindow();

        assertThat(window.businessDate()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(window.available()).isFalse();
        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-20T14:45:00Z"));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-10-17T20:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-10-20T14:45:00Z"));
    }

    @Test
    void afterDealershipCloseSelectsTheNextOpeningDayDigest() {
        var window = policy(Instant.parse("2026-10-20T02:00:00Z"), Map.of()).getAfterHoursDigestWindow();

        assertThat(window.businessDate()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-20T14:45:00Z"));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));
    }

    private static DealershipHoursPolicy policy(Instant now, Map<LocalDate, DealershipHours> overrides) {
        return new DealershipHoursPolicy(Clock.fixed(now, ZoneId.of("UTC")), ZONE,
                Map.of(DayOfWeek.MONDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.TUESDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.WEDNESDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.THURSDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.FRIDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.SATURDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(14, 0))),
                overrides);
    }
}
