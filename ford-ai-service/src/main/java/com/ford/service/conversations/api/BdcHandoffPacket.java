package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict, null-free BDC handoff packet. Only five fields are supported: model, payment method,
 * approximate down payment when financing, purchase timeframe, and name. Present values are
 * customer-confirmed; unknown values are omitted so a partial packet stays truthful.
 */
@NamedInterface
public record BdcHandoffPacket(
        Optional<String> vehicleModel,
        Optional<String> paymentMethod,
        Optional<String> downPayment,
        Optional<String> purchaseTimeframe,
        Optional<String> customerName) {

    private static final int MAX_LENGTH = 160;

    public BdcHandoffPacket {
        vehicleModel = clean(vehicleModel, "vehicleModel");
        paymentMethod = clean(paymentMethod, "paymentMethod");
        downPayment = clean(downPayment, "downPayment");
        purchaseTimeframe = clean(purchaseTimeframe, "purchaseTimeframe");
        customerName = clean(customerName, "customerName");
    }

    public static BdcHandoffPacket empty() {
        return new BdcHandoffPacket(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public int completeness() {
        return (int) arguments().size();
    }

    public boolean partial() {
        return completeness() < 5;
    }

    /** Produces only supported, known arguments in a stable order; absent values are omitted. */
    public Map<String, String> arguments() {
        var result = new LinkedHashMap<String, String>();
        vehicleModel.ifPresent(value -> result.put("vehicleModel", value));
        paymentMethod.ifPresent(value -> result.put("paymentMethod", value));
        downPayment.ifPresent(value -> result.put("downPayment", value));
        purchaseTimeframe.ifPresent(value -> result.put("purchaseTimeframe", value));
        customerName.ifPresent(value -> result.put("customerName", value));
        return result;
    }

    public static BdcHandoffPacket fromArguments(Map<String, String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return empty();
        }
        return new BdcHandoffPacket(
                Optional.ofNullable(arguments.get("vehicleModel")),
                Optional.ofNullable(arguments.get("paymentMethod")),
                Optional.ofNullable(arguments.get("downPayment")),
                Optional.ofNullable(arguments.get("purchaseTimeframe")),
                Optional.ofNullable(arguments.get("customerName")));
    }

    private static Optional<String> clean(Optional<String> value, String field) {
        Objects.requireNonNull(value, field + " must use Optional.empty() when unknown");
        return value.map(String::strip).filter(text -> !text.isEmpty())
                .map(text -> required(text, field));
    }

    private static String required(String value, String field) {
        if (value.isBlank() || value.length() > MAX_LENGTH || value.contains("\u0000")) {
            throw new IllegalArgumentException("Invalid BDC handoff packet " + field);
        }
        return value.strip();
    }
}
