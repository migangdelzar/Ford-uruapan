package com.ford.service.conversations.application;

import com.ford.service.ai.application.EvidenceGroundedResponsePolicy;

import java.util.List;
import java.util.regex.Pattern;

/** Limits advisor edits to claims already present in the approved draft or customer's message. */
public final class AdvisorReplacementMessagePolicy {
    private static final List<Pattern> UNSUPPORTED_COMMITMENTS = List.of(
            Pattern.compile("(?iu)\\b(?:te|le|les)?\\s*(?:aplicamos|aplico|damos|doy|autorizamos)\\s+(?:un\\s+)?descuento\\b"),
            Pattern.compile("(?iu)\\b(?:cr[eé]dito|financiamiento)\\b.{0,40}\\b(?:aprobado|autorizado|garantizado)\\b"),
            Pattern.compile("(?iu)\\b(?:hay|tenemos|est[aá] disponible|queda|reservamos|apartamos)\\b.{0,48}\\b(?:unidad|unidades|veh[ií]culo|auto|camioneta|stock|inventario)\\b"),
            Pattern.compile("(?iu)\\b(?:te|le|les)?\\s*(?:garantizo|garantizamos|prometo|prometemos)\\b"));

    private final EvidenceGroundedResponsePolicy grounded = new EvidenceGroundedResponsePolicy();

    public Verdict validate(String approvedDraft, boolean draftMayBeApproved,
                            String customerMessage, String replacement) {
        if (replacement == null || replacement.isBlank() || replacement.length() > 2000) {
            return new Verdict(false, "invalid_message");
        }
        if (containsUnsupportedCommitment(replacement)) {
            return new Verdict(false, "unsupported_commitment");
        }
        var evidence = draftMayBeApproved ? approvedDraft : "";
        if (draftMayBeApproved && (evidence == null || evidence.isBlank())) {
            return new Verdict(false, "approved_evidence_missing");
        }
        var result = grounded.evaluate(customerMessage, evidence, replacement);
        return new Verdict(result.accepted(), result.reason());
    }

    public record Verdict(boolean allowed, String reasonCode) { }

    public boolean containsUnsupportedCommitment(String message) {
        return message != null && UNSUPPORTED_COMMITMENTS.stream().anyMatch(pattern -> pattern.matcher(message).find());
    }
}
