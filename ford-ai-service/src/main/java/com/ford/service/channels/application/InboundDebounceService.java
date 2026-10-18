package com.ford.service.channels.application;

import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schedule a single delayed response opportunity per conversation burst.
 *
 * <p>Every inbound message is already durable before it reaches this service, so a
 * missing receipt is a hard error rather than an implicitly dropped message. Opt-out,
 * an explicit human request, and an urgent complaint bypass the delay.</p>
 */
public class InboundDebounceService implements InboundDebounceGate {

    private static final Pattern OPT_OUT = Pattern.compile(
            "\\b(no me (escrib|contact|envi|molest|lleg|mand)\\w*"
                    + "|dejen? de (escrib|contact|envi|mand)\\w*"
                    + "|no quiero (que me escrib|recibir|que me contact|que me envi)\\w*"
                    + "|baja de (la )?lista|darme de baja|unsubscribe|stop)\\b");
    private static final Pattern URGENT = Pattern.compile(
            "\\b(urgent\\w*|inmediat\\w*|lo antes posible|cuanto antes|ahora mismo|de inmediato|emergencia)\\b");
    private static final Pattern HUMAN_REQUEST = Pattern.compile(
            "(\\b(hablar|comunicar\\w*|conect\\w*|contact\\w*|transferir\\w*|pasarme|atienda|atiendan)\\b"
                    + ".{0,40}\\b(asesor\\w*|especialista\\w*|persona|humano|agente|vendedor\\w*)\\b)"
                    + "|(\\b(quiero|deseo|necesito|prefiero)\\b.{0,20}\\b(asesor\\w*|especialista\\w*|persona|humano|agente)\\b)"
                    + "|(\\b(asesor\\w*|especialista\\w*|humano)\\b.{0,40}\\b(llam\\w*|contact\\w*|hablar|comunicar\\w*|conect\\w*)\\b)");

    private final InboundDebounceRepository repository;
    private final AcceptedInboundMessageRepository messages;
    private final Clock clock;
    private final Duration debounce;

    public InboundDebounceService(InboundDebounceRepository repository,
                                  AcceptedInboundMessageRepository messages,
                                  Clock clock,
                                  Duration debounce) {
        this.repository = Objects.requireNonNull(repository);
        this.messages = Objects.requireNonNull(messages);
        this.clock = Objects.requireNonNull(clock);
        this.debounce = Objects.requireNonNull(debounce);
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
    }

    public void schedule(InboundMessageAccepted event) {
        Objects.requireNonNull(event, "inbound event is required");
        var message = messages.find(event.tenantId(), event.conversationId(), event.receiptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot debounce an inbound message without a durable receipt: receiptId="
                                + event.receiptId()));
        var now = clock.instant();
        repository.schedule(event, bypassesDebounce(message.text()) ? now : now.plus(debounce));
    }

    @Override
    public boolean isDue(UUID tenantId, UUID conversationId, UUID receiptId) {
        return repository.isDue(tenantId, conversationId, receiptId, clock.instant());
    }

    static boolean bypassesDebounce(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        var normalized = normalize(text);
        return OPT_OUT.matcher(normalized).find()
                || URGENT.matcher(normalized).find()
                || HUMAN_REQUEST.matcher(normalized).find();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
