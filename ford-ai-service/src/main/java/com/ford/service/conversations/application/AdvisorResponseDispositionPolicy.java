package com.ford.service.conversations.application;

import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Derives server-owned advisor routing signals from the accepted turn and assistant result. */
public final class AdvisorResponseDispositionPolicy {
    private static final Pattern SERIOUS_COMPLAINT = Pattern.compile(
            "\\b(fraude|estafa|me robaron|me estafaron|denunciar|presentar una denuncia|voy a demandar|demanda|"
                    + "discriminacion|me discriminaron|amenaza|me amenazaron)\\b");
    private static final Pattern INVENTORY_PROMISE = Pattern.compile(
            "\\b(?:hay|tenemos|est[aá] disponible|queda|reservamos|apartamos|contamos con)\\b.{0,55}\\b(?:unidad|unidades|veh[ií]culo|auto|camioneta|stock|inventario)\\b"
                    + "|\\b(?:unidad|unidades|veh[ií]culo|auto|camioneta)\\b.{0,45}\\b(?:disponible|en existencia|en stock)\\b");
    private static final Pattern DISCOUNT_COMMITMENT = Pattern.compile(
            "\\b(?:te|le|les)?\\s*(?:aplicamos|aplico|damos|doy|autorizamos|autorizo|garantizamos)\\s+(?:un\\s+)?descuento\\b"
                    + "|\\bdescuento\\s+(?:de|del)\\s+\\$?\\d");
    private static final Pattern FINANCE_COMMITMENT = Pattern.compile(
            "\\b(?:cr[eé]dito|financiamiento)\\b.{0,50}\\b(?:aprobado|autorizado|garantizado|preaprobado)\\b"
                    + "|\\b(?:aprobado|autorizado|garantizado)\\b.{0,40}\\b(?:cr[eé]dito|financiamiento)\\b");
    private static final Pattern FAILURE_REPLY = Pattern.compile(
            "\\b(no pude|no puedo confirmar|no encontr[eé]|no tengo (?:esa )?informaci[oó]n|no me fue posible)\\b");

    private final AdvisorResponseRoutingPolicy routing = new AdvisorResponseRoutingPolicy();
    private final CustomerHandoffAuthorizationPolicy humanAuthorization = new CustomerHandoffAuthorizationPolicy();

    public AdvisorResponseDisposition beforeAssistant(AcceptedInboundMessage inbound, int consecutiveFailures) {
        Objects.requireNonNull(inbound, "accepted inbound message is required");
        var signals = EnumSet.noneOf(AdvisorResponseRoutingPolicy.Signal.class);
        var text = normalize(inbound.text());
        if (humanAuthorization.isDirectRequest(inbound.text())) signals.add(AdvisorResponseRoutingPolicy.Signal.HUMAN_REQUEST);
        else if (SERIOUS_COMPLAINT.matcher(text).find()) signals.add(AdvisorResponseRoutingPolicy.Signal.SERIOUS_COMPLAINT);
        if (isSensitiveDocument(inbound)) signals.add(AdvisorResponseRoutingPolicy.Signal.SENSITIVE_DOCUMENT);
        return routing.decide(new AdvisorResponseRoutingPolicy.Signals(signals, consecutiveFailures));
    }

    public AdvisorResponseDisposition afterAssistant(AcceptedInboundMessage inbound, int consecutiveFailures,
                                                      AssistantReply reply) {
        Objects.requireNonNull(inbound, "accepted inbound message is required");
        Objects.requireNonNull(reply, "assistant reply is required");
        var signals = EnumSet.noneOf(AdvisorResponseRoutingPolicy.Signal.class);
        var text = normalize(reply.text());
        if (INVENTORY_PROMISE.matcher(text).find()) signals.add(AdvisorResponseRoutingPolicy.Signal.INVENTORY_PROMISE);
        if (DISCOUNT_COMMITMENT.matcher(text).find()) signals.add(AdvisorResponseRoutingPolicy.Signal.DISCOUNT_COMMITMENT);
        if (FINANCE_COMMITMENT.matcher(text).find()) signals.add(AdvisorResponseRoutingPolicy.Signal.FINANCE_COMMITMENT);
        if (hasMissingEvidence(reply.decisionTrace())) signals.add(AdvisorResponseRoutingPolicy.Signal.RESPONSE_REVIEW);
        if (reply.handoffRequired()) signals.add(AdvisorResponseRoutingPolicy.Signal.MODEL_REQUESTED_REVIEW);
        var failedSubstantiveTurn = FAILURE_REPLY.matcher(text).find();
        if (failedSubstantiveTurn && signals.isEmpty()) {
            signals.add(AdvisorResponseRoutingPolicy.Signal.RESPONSE_REVIEW);
        }
        return routing.decide(new AdvisorResponseRoutingPolicy.Signals(signals,
                consecutiveFailures + (failedSubstantiveTurn ? 1 : 0)));
    }

    private static boolean isSensitiveDocument(AcceptedInboundMessage inbound) {
        if (inbound.kind() != InboundMessageKind.DOCUMENT) return false;
        return inbound.attachments().stream().anyMatch(attachment -> {
            var mime = attachment.declaredMimeType() == null ? "" : attachment.declaredMimeType().toLowerCase(Locale.ROOT);
            var name = attachment.filename() == null ? "" : normalize(attachment.filename());
            return mime.equals("application/pdf") || mime.startsWith("image/")
                    || name.matches(".*(ine|identificaci[oó]n|licencia|pasaporte|estado de cuenta|comprobante|n[oó]mina|constancia).*" );
        });
    }

    private static boolean hasMissingEvidence(java.util.List<DecisionTraceEvent> trace) {
        return trace.stream().filter(event -> event.stage().equals("evidence"))
                .anyMatch(event -> Set.of("unknown", "missing", "empty", "failed", "conflicted", "blocked")
                        .contains(event.outcome()));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
