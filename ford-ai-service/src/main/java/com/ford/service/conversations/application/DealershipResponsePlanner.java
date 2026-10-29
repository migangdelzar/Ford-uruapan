package com.ford.service.conversations.application;

import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.conversations.api.AssistantReply;

import java.time.Instant;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Plans after-hours behavior without inventing response times. Supported catalog/knowledge questions stay
 * answerable, accepted handoffs are routed to the BDC queue, and any holiday/special-hours state that the
 * operator has not configured is surfaced as unresolved instead of guessed.
 */
public final class DealershipResponsePlanner {

    public static final String AFTER_HOURS_NOTICE = "La agencia está cerrada ahora.";
    private static final String UNKNOWN_HOLIDAY_CALENDAR_NOTICE = "El horario festivo de hoy no está configurado.";

    private static final Set<Intent> ANSWERABLE_AFTER_HOURS = EnumSet.of(Intent.CATALOG, Intent.INVENTORY,
            Intent.OFFERS, Intent.FINANCE, Intent.KNOWLEDGE, Intent.DEALERSHIP_INFO, Intent.SERVICE_SUPPORT);

    private final DealershipHoursPolicy hours;
    private final IntentRouter intentRouter;

    public DealershipResponsePlanner(DealershipHoursPolicy hours) {
        this(hours, new IntentRouter());
    }

    public DealershipResponsePlanner(DealershipHoursPolicy hours, IntentRouter intentRouter) {
        this.hours = Objects.requireNonNull(hours);
        this.intentRouter = Objects.requireNonNull(intentRouter);
    }

    public ResponsePlan plan(String inboundText, boolean handoffRequired) {
        var status = hours.current();
        var afterHours = !status.open();
        var catalogAnswerable = ANSWERABLE_AFTER_HOURS.contains(intentRouter.route(inboundText));
        var asksHours = asksAboutHours(inboundText);
        var asksFollowUp = asksAboutFollowUp(inboundText);
        var asksSpecialHours = asksAboutSpecialHours(inboundText);
        var afterHoursNotice = afterHours && (asksHours || asksFollowUp)
                ? AFTER_HOURS_NOTICE + (asksSpecialHours && !status.holidayCalendarConfigured()
                        ? " " + UNKNOWN_HOLIDAY_CALENDAR_NOTICE : "")
                : null;
        return new ResponsePlan(status.open(), status.nextOpening(), afterHours, catalogAnswerable,
                handoffRequired, status.holidayCalendarConfigured(),
                afterHoursNotice);
    }

    /** Keeps the assistant answer and, outside hours, appends an honest notice with no response ETA. */
    public AssistantReply apply(AssistantReply reply, String inboundText) {
        if (reply.handoffRequired()) {
            return reply;
        }
        var plan = plan(inboundText, false);
        if (plan.afterHoursNotice() == null || reply.text().contains(plan.afterHoursNotice())) {
            return reply;
        }
        return new AssistantReply(reply.text() + " " + plan.afterHoursNotice(), reply.handoffRequired(),
                reply.decision(), reply.journeyProposal());
    }

    private static boolean asksAboutHours(String inboundText) {
        return containsAny(normalize(inboundText), "horario", "a que hora", "que horas", "abren", "abierto",
                "abierta", "cierran", "cerrado", "cerrada", "atienden");
    }

    private static boolean asksAboutSpecialHours(String inboundText) {
        return containsAny(normalize(inboundText), "hoy", "festivo", "feriado", "horario especial");
    }

    private static boolean asksAboutFollowUp(String inboundText) {
        return containsAny(normalize(inboundText), "seguimiento", "asesor", "asesora", "me llamen",
                "llamada", "contactar");
    }

    private static boolean containsAny(String value, String... terms) {
        for (var term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    public record ResponsePlan(boolean open, Instant nextOpening, boolean afterHours,
                               boolean catalogAnswerableAfterHours, boolean handoffToBdcQueue,
                               boolean holidayCalendarResolved, String afterHoursNotice) { }
}
