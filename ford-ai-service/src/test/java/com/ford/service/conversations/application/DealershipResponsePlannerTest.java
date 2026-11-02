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

class DealershipResponsePlannerTest {
    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
    // 2026-10-19 is Monday; 16:00Z is 10:00 local (open), 02:00Z next day is 20:00 local (closed).
    private static final Instant MONDAY_OPEN = Instant.parse("2026-10-19T16:00:00Z");
    private static final Instant MONDAY_AFTER_HOURS = Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void catalogQuestionsRemainAnswerableAfterHours() {
        var plan = planner(MONDAY_AFTER_HOURS, Map.of()).plan("¿Cuál es el precio de la Ranger?", false);

        assertThat(plan.afterHours()).isTrue();
        assertThat(plan.catalogAnswerableAfterHours()).isTrue();
        assertThat(plan.afterHoursNotice()).isNull();
    }

    @Test
    void addsHoursNoticeOnlyWhenCustomerAsksAboutHoursOrFollowUp() {
        var planner = planner(MONDAY_AFTER_HOURS, Map.of());
        var catalogReply = new com.ford.service.conversations.api.AssistantReply(
                "La Ranger 2026 cuesta $900,000 MXN.", false);
        var hoursReply = new com.ford.service.conversations.api.AssistantReply(
                "El horario es de lunes a viernes, de 9:00 a 19:00.", false);

        assertThat(planner.apply(catalogReply, "¿Cuál es el precio de la Ranger?").text())
                .isEqualTo(catalogReply.text());
        assertThat(planner.apply(hoursReply, "¿A qué hora cierran hoy?").text())
                .contains("La agencia está cerrada ahora.");
    }

    @Test
    void acceptedHandoffEntersTheBdcQueueRegardlessOfHours() {
        var plan = planner(MONDAY_AFTER_HOURS, Map.of()).plan("Quiero hablar con un asesor", true);

        assertThat(plan.handoffToBdcQueue()).isTrue();
    }

    @Test
    void afterHoursNoticeNeverInventsAResponseEta() {
        var plan = planner(MONDAY_AFTER_HOURS, Map.of()).plan("¿Están abiertos hoy?", false);

        assertThat(plan.afterHoursNotice())
                .doesNotContainIgnoringCase("minuto")
                .doesNotContain("en 1")
                .doesNotMatch("(?s).*\\d{1,2}:\\d{2}.*")
                .doesNotContainIgnoringCase("hora y media");
        assertThat(plan.afterHoursNotice()).contains(DealershipResponsePlanner.AFTER_HOURS_NOTICE);
        assertThat(plan.afterHoursNotice()).doesNotContainIgnoringCase("dejaré su solicitud registrada");
        assertThat(plan.afterHoursNotice()).contains("horario festivo de hoy no está configurado");
    }

    @Test
    void unresolvedHolidayCalendarIsReportedHonestly() {
        assertThat(planner(MONDAY_OPEN, Map.of()).plan("Hola", false).holidayCalendarResolved()).isFalse();
        assertThat(planner(MONDAY_OPEN, Map.of(LocalDate.parse("2026-11-16"), DealershipHours.closed()))
                .plan("Hola", false).holidayCalendarResolved()).isTrue();
    }

    @Test
    void duringHoursThereIsNoAfterHoursNotice() {
        var plan = planner(MONDAY_OPEN, Map.of()).plan("Hola", false);

        assertThat(plan.open()).isTrue();
        assertThat(plan.afterHours()).isFalse();
        assertThat(plan.afterHoursNotice()).isNull();
    }

    private static DealershipResponsePlanner planner(Instant now, Map<LocalDate, DealershipHours> overrides) {
        return new DealershipResponsePlanner(new DealershipHoursPolicy(Clock.fixed(now, ZONE), ZONE,
                Map.of(DayOfWeek.MONDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.TUESDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.WEDNESDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.THURSDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.FRIDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.SATURDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(14, 0))),
                overrides));
    }
}
