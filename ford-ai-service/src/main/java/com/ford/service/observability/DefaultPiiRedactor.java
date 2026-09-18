package com.ford.service.observability;

import java.util.regex.Pattern;

public final class DefaultPiiRedactor implements PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?52[ -]?)?(?:\\(?\\d{3}\\)?[ -]?)\\d{3}[ -]?\\d{4}(?!\\d)");
    private static final Pattern MONEY = Pattern.compile("(?i)(?<!\\w)(?:mxn|\\$)?[ ]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?(?!\\w)");
    private static final Pattern PROVIDER_ID = Pattern.compile("(?i)(?:\\b(?:id|wamid|mid)[=: ]+|\\bwamid\\.)[^\\s,}]+");

    @Override
    public RedactedPayload redact(String payload) {
        if (payload == null) {
            return new RedactedPayload("");
        }
        var value = EMAIL.matcher(payload).replaceAll("[REDACTED]");
        value = PHONE.matcher(value).replaceAll("[REDACTED]");
        value = MONEY.matcher(value).replaceAll("[REDACTED]");
        value = PROVIDER_ID.matcher(value).replaceAll("[REDACTED]");
        return new RedactedPayload(value);
    }
}
