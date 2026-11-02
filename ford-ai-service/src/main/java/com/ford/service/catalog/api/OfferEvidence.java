package com.ford.service.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.JsonNode;

public record OfferEvidence(String offerId, String model, Integer modelYear, String title, String offerType,
                               String provider, BigDecimal cashPrice, BigDecimal monthlyPayment,
                               BigDecimal downPaymentAmount, BigDecimal downPaymentPercent,
                               Integer termMonths, Integer monthsWithoutInterest,
                               BigDecimal annualInterestRate, BigDecimal catPercent,
                               String currency, LocalDate validFrom, LocalDate validUntil,
                               String validityStatus, boolean requiresCreditApproval,
                               String conditions, String sourceUrl, String modelPageUrl,
                               Instant observedAt, JsonNode benefits, JsonNode termConflict,
                               BigDecimal taxRatePercent, BigDecimal openingCommissionRatePercent,
                               BigDecimal maximumDownPaymentPercent, BigDecimal lifeInsurancePrice) {
    public OfferEvidence(String offerId, String model, Integer modelYear, String title, String offerType,
                            String provider, BigDecimal cashPrice, BigDecimal monthlyPayment,
                            BigDecimal downPaymentAmount, BigDecimal downPaymentPercent,
                            Integer termMonths, Integer monthsWithoutInterest,
                            BigDecimal annualInterestRate, BigDecimal catPercent,
                            String currency, LocalDate validFrom, LocalDate validUntil,
                            String validityStatus, boolean requiresCreditApproval,
                            String conditions, String sourceUrl, String modelPageUrl, Instant observedAt) {
        this(offerId, model, modelYear, title, offerType, provider, cashPrice, monthlyPayment, downPaymentAmount,
                downPaymentPercent, termMonths, monthsWithoutInterest, annualInterestRate, catPercent, currency,
                validFrom, validUntil, validityStatus, requiresCreditApproval, conditions, sourceUrl, modelPageUrl,
                observedAt, null, null, null, null, null, null);
    }

    public OfferEvidence(String offerId, String model, Integer modelYear, String title, String offerType,
                            String provider, BigDecimal cashPrice, BigDecimal monthlyPayment,
                            BigDecimal downPaymentAmount, BigDecimal downPaymentPercent,
                            Integer termMonths, Integer monthsWithoutInterest,
                            BigDecimal annualInterestRate, BigDecimal catPercent,
                            String currency, LocalDate validFrom, LocalDate validUntil,
                            String validityStatus, boolean requiresCreditApproval,
                            String conditions, String sourceUrl, String modelPageUrl, Instant observedAt,
                            JsonNode benefits, JsonNode termConflict) {
        this(offerId, model, modelYear, title, offerType, provider, cashPrice, monthlyPayment,
                downPaymentAmount, downPaymentPercent, termMonths, monthsWithoutInterest, annualInterestRate,
                catPercent, currency, validFrom, validUntil, validityStatus, requiresCreditApproval,
                conditions, sourceUrl, modelPageUrl, observedAt, benefits, termConflict, null, null, null, null);
    }
}
