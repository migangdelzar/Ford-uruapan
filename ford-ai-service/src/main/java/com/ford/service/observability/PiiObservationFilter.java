package com.ford.service.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public final class PiiObservationFilter implements ObservationFilter {

    private static final String[] SENSITIVE_FRAGMENTS = {
            "prompt", "completion", "email", "phone", "customer.name", "lead.name", "full_name",
            "government.id", "curp", "rfc", "bank", "account.number", "income", "mxn", "amount",
            "raw_payload", "request.body", "response.body", "tool.call.arguments", "tool.call.result",
            "provider.id", "provider.message.id", "message.id", "wamid"
    };

    @Override
    public Observation.Context map(Observation.Context context) {
        var sensitiveKeys = context.getAllKeyValues().stream()
                .map(KeyValue::getKey)
                .filter(PiiObservationFilter::isSensitive)
                .distinct()
                .toArray(String[]::new);
        context.removeLowCardinalityKeyValues(sensitiveKeys);
        context.removeHighCardinalityKeyValues(sensitiveKeys);
        return context;
    }

    private static boolean isSensitive(String key) {
        var normalized = key.toLowerCase(Locale.ROOT);
        for (var fragment : SENSITIVE_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
