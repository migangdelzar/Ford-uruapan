package com.ford.service.conversations.api;

import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import com.ford.service.handoffcontact.api.PreferredContactChannel;
import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Confirmed, bounded shopping context for an authorized specialist handoff. */
@NamedInterface
public record BdcHandoffPacket(Optional<String> vehicleModel, Optional<String> vehicleTrim,
                               Optional<String> needs, Optional<String> purchaseTimeframe,
                               Optional<String> buyerPriceRange, Optional<String> tradeInInterest,
                               Optional<String> appointmentInterest, Optional<String> customerName,
                               Optional<AlternateContactValue> alternateContact,
                               Optional<PreferredContactChannel> preferredContactChannel) {
    private static final int MAX_LENGTH = 160;
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?\\d[\\d .()-]{8,}\\d(?!\\d)");

    public BdcHandoffPacket {
        vehicleModel = clean(vehicleModel, "vehicleModel", true);
        vehicleTrim = clean(vehicleTrim, "vehicleTrim", true);
        needs = clean(needs, "needs", true);
        purchaseTimeframe = clean(purchaseTimeframe, "purchaseTimeframe", true);
        buyerPriceRange = clean(buyerPriceRange, "buyerPriceRange", true);
        tradeInInterest = clean(tradeInInterest, "tradeInInterest", true);
        appointmentInterest = clean(appointmentInterest, "appointmentInterest", true);
        customerName = clean(customerName, "customerName", false);
        Objects.requireNonNull(alternateContact, "alternateContact must use Optional.empty() when unknown");
        Objects.requireNonNull(preferredContactChannel,
                "preferredContactChannel must use Optional.empty() when unknown");
    }

    public BdcHandoffPacket(Optional<String> vehicleModel, Optional<String> vehicleTrim,
                            Optional<String> needs, Optional<String> purchaseTimeframe,
                            Optional<String> buyerPriceRange, Optional<String> tradeInInterest,
                            Optional<String> appointmentInterest, Optional<String> customerName) {
        this(vehicleModel, vehicleTrim, needs, purchaseTimeframe, buyerPriceRange, tradeInInterest,
                appointmentInterest, customerName, Optional.empty(), Optional.empty());
    }

    public static BdcHandoffPacket empty() {
        return new BdcHandoffPacket(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Persisted schema allows 0..5; this score is present shopping fields, capped at five. */
    public int completeness() {
        var shoppingFields = java.util.stream.Stream.of(vehicleModel, vehicleTrim, needs, purchaseTimeframe,
                buyerPriceRange, tradeInInterest, appointmentInterest).filter(Optional::isPresent).count();
        return (int) Math.min(5, shoppingFields);
    }
    public boolean partial() { return completeness() < 5; }

    public Map<String, String> arguments() {
        var result = new LinkedHashMap<String, String>();
        vehicleModel.ifPresent(value -> result.put("vehicleModel", value));
        vehicleTrim.ifPresent(value -> result.put("vehicleTrim", value));
        needs.ifPresent(value -> result.put("needs", value));
        purchaseTimeframe.ifPresent(value -> result.put("purchaseTimeframe", value));
        buyerPriceRange.ifPresent(value -> result.put("buyerPriceRange", value));
        tradeInInterest.ifPresent(value -> result.put("tradeInInterest", value));
        appointmentInterest.ifPresent(value -> result.put("appointmentInterest", value));
        customerName.ifPresent(value -> result.put("customerName", value));
        alternateContact.ifPresent(value -> {
            result.put("alternateContactType", value.type().name());
            result.put("alternateContactValue", value.normalizedValue());
            result.put("alternateContactConsentedAt", value.consentedAt().toString());
            result.put("alternateContactMessageId", value.consentMessageId());
            result.put("alternateContactPolicyVersion", value.policyVersion());
        });
        preferredContactChannel.ifPresent(value -> result.put("preferredContactChannel", value.name()));
        return result;
    }

    public BdcHandoffPacket withAlternateContact(AlternateContactValue contact) {
        var channel = contact == null ? Optional.<PreferredContactChannel>empty()
                : Optional.of(contact.type() == ContactType.PHONE
                        ? PreferredContactChannel.ALTERNATE_PHONE : PreferredContactChannel.ALTERNATE_EMAIL);
        return new BdcHandoffPacket(vehicleModel, vehicleTrim, needs, purchaseTimeframe, buyerPriceRange,
                tradeInInterest, appointmentInterest, customerName, Optional.ofNullable(contact), channel);
    }

    public BdcHandoffPacket withPreferredContactChannel(PreferredContactChannel channel) {
        return new BdcHandoffPacket(vehicleModel, vehicleTrim, needs, purchaseTimeframe, buyerPriceRange,
                tradeInInterest, appointmentInterest, customerName, alternateContact, Optional.ofNullable(channel));
    }

    /** Unknown JSON keys from legacy records are intentionally ignored. */
    public static BdcHandoffPacket fromArguments(Map<String, String> arguments) {
        if (arguments == null || arguments.isEmpty()) return empty();
        return new BdcHandoffPacket(Optional.ofNullable(arguments.get("vehicleModel")),
                Optional.ofNullable(arguments.get("vehicleTrim")), Optional.ofNullable(arguments.get("needs")),
                Optional.ofNullable(arguments.get("purchaseTimeframe")), Optional.ofNullable(arguments.get("buyerPriceRange")),
                Optional.ofNullable(arguments.get("tradeInInterest")), Optional.ofNullable(arguments.get("appointmentInterest")),
                Optional.ofNullable(arguments.get("customerName")), parseContact(arguments),
                parsePreferredChannel(arguments.get("preferredContactChannel")));
    }

    @Override public String toString() { return "BdcHandoffPacket[shopping context omitted]"; }

    private static Optional<AlternateContactValue> parseContact(Map<String, String> arguments) {
        var type = arguments.get("alternateContactType");
        var value = arguments.get("alternateContactValue");
        var consentedAt = arguments.get("alternateContactConsentedAt");
        var messageId = arguments.get("alternateContactMessageId");
        var policyVersion = arguments.get("alternateContactPolicyVersion");
        if (type == null || value == null || consentedAt == null || messageId == null || policyVersion == null)
            return Optional.empty();
        try {
            return Optional.of(new AlternateContactValue(ContactType.valueOf(type), value,
                    Instant.parse(consentedAt), messageId, policyVersion));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<PreferredContactChannel> parsePreferredChannel(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(PreferredContactChannel.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> clean(Optional<String> value, String field, boolean redactContact) {
        Objects.requireNonNull(value, field + " must use Optional.empty() when unknown");
        return value.map(String::strip).filter(text -> !text.isEmpty()).map(text -> {
            if (redactContact) text = PHONE.matcher(EMAIL.matcher(text).replaceAll("[dato de contacto omitido]"))
                    .replaceAll("[dato de contacto omitido]");
            else if (EMAIL.matcher(text).find() || PHONE.matcher(text).find()) return "";
            if (text.length() > MAX_LENGTH || text.contains("\u0000"))
                throw new IllegalArgumentException("Invalid BDC handoff packet " + field);
            return text;
        }).filter(text -> !text.isBlank());
    }
}
