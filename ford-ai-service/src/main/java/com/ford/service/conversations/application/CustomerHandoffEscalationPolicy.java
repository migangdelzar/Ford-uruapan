package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides when the application must escalate to a human queue regardless of the model output:
 * an explicit human request, an upset/complaint turn, or a second refusal to share qualification
 * details. Kept in application code so DeepSeek output cannot create external actions on its own.
 */
public final class CustomerHandoffEscalationPolicy {

    private static final Pattern UPSET_OR_COMPLAINT = Pattern.compile(
            "(?iu)\\b(molest\\w*|enojad\\w*|enoj\\w*|furios\\w*|indignad\\w*|hart[oa]s?|"
                    + "frustrad\\w*|p[eé]sim\\w*|deficiente|inaceptable|inadmisible|groser\\w*|"
                    + "mala atenci[oó]n|mal servicio|me quejo|quiero (?:poner )?una queja|"
                    + "queja|reclam\\w*|exijo)\\b");
    private static final Pattern QUALIFICATION_REFUSAL = Pattern.compile(
            "(?iu)\\b(?:no|nunca) (?:quiero|deseo|voy a|pienso|le voy a) "
                    + "(?:compartir|dar|proporcionar|decir|facilitar|revelar)\\b"
                    + "|\\bprefiero no (?:dar|decir|compartir|proporcionar)\\b"
                    + "|\\bme niego a (?:dar|compartir|decir|proporcionar)\\b"
                    + "|\\bno le (?:voy a )?(?:doy|digo|comparto|proporciono)\\b");

    private final CustomerHandoffAuthorizationPolicy authorization = new CustomerHandoffAuthorizationPolicy();

    public boolean shouldEscalate(String currentText, ConversationMemoryContext memory) {
        if (currentText == null || currentText.isBlank()
                || authorization.isDecline(currentText)) {
            return false;
        }
        if (authorization.isDirectRequest(currentText)) {
            return true;
        }
        if (isUpsetOrComplaint(currentText)) {
            return true;
        }
        return isQualificationRefusal(currentText) && priorRefusals(memory) >= 1;
    }

    public boolean isUpsetOrComplaint(String userMessage) {
        return userMessage != null && !userMessage.isBlank()
                && UPSET_OR_COMPLAINT.matcher(normalize(userMessage)).find();
    }

    public boolean isQualificationRefusal(String userMessage) {
        return userMessage != null && !userMessage.isBlank()
                && QUALIFICATION_REFUSAL.matcher(normalize(userMessage)).find();
    }

    private int priorRefusals(ConversationMemoryContext memory) {
        if (memory == null) {
            return 0;
        }
        return (int) memory.recentMessages().stream()
                .filter(message -> message.direction() == ConversationMessage.Direction.INBOUND)
                .filter(message -> isQualificationRefusal(message.content()))
                .count();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }
}
