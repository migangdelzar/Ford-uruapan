package com.ford.service.conversations.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DealershipHoursFactoryTest {
    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

    @Test
    void buildsTheWeeklyScheduleFromProperties() {
        var policy = DealershipHoursFactory.create(Clock.fixed(Instant.parse("2026-10-19T16:00:00Z"), ZONE),
                new ConversationProperties().hours());

        // Monday 10:00 local -> open
        assertThat(policy.current().open()).isTrue();
    }

    @Test
    void sundayIsClosedAndNextOpeningIsMondayMorning() {
        // 2026-10-18 is Sunday 12:00 local.
        var policy = DealershipHoursFactory.create(Clock.fixed(Instant.parse("2026-10-18T18:00:00Z"), ZONE),
                new ConversationProperties().hours());

        var status = policy.current();
        assertThat(status.open()).isFalse();
        assertThat(status.nextOpening().atZone(ZONE).toLocalDate().getDayOfWeek())
                .isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(status.nextOpening().atZone(ZONE).toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    @Test
    void calendarOverridesAreAppliedAndReportedAsConfigured() {
        var hours = new ConversationProperties().hours();
        hours.calendar().put("2026-12-25", "closed");
        var policy = DealershipHoursFactory.create(Clock.fixed(Instant.parse("2026-12-25T17:00:00Z"), ZONE), hours);

        assertThat(policy.holidayCalendarConfigured()).isTrue();
        assertThat(policy.current().open()).isFalse();
    }

    @Test
    void unconfiguredCalendarIsReportedAsUnresolved() {
        var policy = DealershipHoursFactory.create(
                Clock.fixed(Instant.parse("2026-10-19T16:00:00Z"), ZONE), new ConversationProperties().hours());

        assertThat(policy.holidayCalendarConfigured()).isFalse();
        assertThat(Map.of()).isEmpty();
    }
}
