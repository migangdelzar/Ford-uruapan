package com.ford.service.handoffcontact.application;

import com.ford.service.handoffcontact.api.AlternateContactValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlternateContactCapturePolicyTest {
    private final AlternateContactCapturePolicy policy = new AlternateContactCapturePolicy(new AlternateContactRedactor());
    private final Instant receivedAt = Instant.parse("2026-10-23T19:00:00Z");

    @Test
    void acceptsMexicanPhoneOnlyWithClearSameMessageConsentAndNormalizesIt() {
        var result = policy.extract("Autorizo compartir mi número con Ford: 55 1234 5678",
                "message-1", receivedAt);

        assertThat(result.acceptedValues()).singleElement()
                .extracting(AlternateContactValue::normalizedValue)
                .isEqualTo("+525512345678");
        assertThat(result.sanitizedText()).doesNotContain("55 1234 5678");
    }

    @Test
    void acceptsEmailAndLowercasesOnlyItsDomain() {
        var result = policy.extract("Sí, autorizo compartir este correo con Ford: Ana.Rios@Example.COM",
                "message-2", receivedAt);

        assertThat(result.acceptedValues()).singleElement()
                .extracting(AlternateContactValue::normalizedValue)
                .isEqualTo("Ana.Rios@example.com");
        assertThat(result.sanitizedText()).doesNotContain("Ana.Rios@Example.COM");
    }

    @Test
    void acceptsExactValueWhenConsentFollowsItInSameMessage() {
        var result = policy.extract("Mi correo es Ana.Rios@example.com y autorizo compartirlo con Ford",
                "message-8", receivedAt);

        assertThat(result.acceptedValues()).singleElement()
                .extracting(AlternateContactValue::normalizedValue)
                .isEqualTo("Ana.Rios@example.com");
    }

    @Test
    void redactsContactEvenWhenConsentIsMissing() {
        var result = policy.extract("Mi correo es ana@example.com", "message-3", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("ana@example.com")
                .contains("[dato de contacto omitido]");
    }

    @Test
    void rejectsDeclinedOrAmbiguousConsentAndStillRedactsValue() {
        var declined = policy.extract("No autorizo compartir mi número 55 1234 5678",
                "message-4", receivedAt);
        var ambiguous = policy.extract("Tal vez puedes usar mi correo ana@example.com",
                "message-5", receivedAt);

        assertThat(declined.acceptedValues()).isEmpty();
        assertThat(ambiguous.acceptedValues()).isEmpty();
        assertThat(declined.sanitizedText()).doesNotContain("55 1234 5678");
        assertThat(ambiguous.sanitizedText()).doesNotContain("ana@example.com");
    }

    @Test
    void rejectsExplicitConsentRevocationAndStillRedactsValue() {
        var noConsent = policy.extract("No consiento compartir mi correo: ana@example.com",
                "message-no-consent", receivedAt);
        var noPermission = policy.extract("No doy permiso para compartir mi correo: ana@example.com",
                "message-no-permission", receivedAt);
        var noTePermission = policy.extract("No te doy permiso para compartir mi correo: ana@example.com",
                "message-no-te-permission", receivedAt);
        var noLeAuthorization = policy.extract("No le autorizo compartir mi correo: ana@example.com",
                "message-no-le-authorization", receivedAt);
        var consentThenRevocation = policy.extract(
                "Autorizo compartir mi correo con Ford: ana@example.com, pero no lo compartas",
                "message-consent-then-revocation", receivedAt);
        var revokedAuthorization = policy.extract(
                "Autorizo compartir mi correo con Ford: ana@example.com, pero revoco mi autorización",
                "message-revoked-authorization", receivedAt);
        var withdrawnConsent = policy.extract(
                "Autorizo compartir mi correo con Ford: ana@example.com, pero retiro mi consentimiento",
                "message-withdrawn-consent", receivedAt);

        assertThat(noConsent.acceptedValues()).isEmpty();
        assertThat(noPermission.acceptedValues()).isEmpty();
        assertThat(noTePermission.acceptedValues()).isEmpty();
        assertThat(noLeAuthorization.acceptedValues()).isEmpty();
        assertThat(consentThenRevocation.acceptedValues()).isEmpty();
        assertThat(revokedAuthorization.acceptedValues()).isEmpty();
        assertThat(withdrawnConsent.acceptedValues()).isEmpty();
        assertThat(noConsent.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(noPermission.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(noTePermission.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(noLeAuthorization.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(consentThenRevocation.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(revokedAuthorization.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(withdrawnConsent.sanitizedText()).doesNotContain("ana@example.com");
    }

    @Test
    void rejectionOverridesDistantPositiveConsentAcrossTheWholeMessage() {
        var text = "No te autorizo compartir mi correo. " + "contexto ".repeat(30)
                + "Autorizo compartir mi correo con Ford: ana@example.com";

        var result = policy.extract(text, "message-distant-revocation", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("ana@example.com");
    }

    @Test
    void rejectsAnotherPersonsContactAndConsentRestrictedToAnotherRecipient() {
        var otherPerson = policy.extract(
                "Autorizo compartir el correo de mi hermana con Ford; el mío es ana@example.com",
                "message-other-person", receivedAt);
        var restrictedRecipient = policy.extract(
                "Autorizo compartir mi correo con Ford, pero solo con mi aseguradora: ana@example.com",
                "message-other-recipient", receivedAt);
        var unrelatedConsent = policy.extract(
                "Autorizo compartir la promoción con Ford. Mi correo es ana@example.com",
                "message-unrelated-consent", receivedAt);
        var familyContactAfterConsent = policy.extract(
                "Autorizo compartir mi correo con Ford, el de mi tía es ana@example.com",
                "message-family-contact-after-consent", receivedAt);
        var explicitlyUnauthorizedFord = policy.extract(
                "Autorizo compartir mi correo ana@example.com con Toyota; Ford no tiene permiso",
                "message-unauthorized-recipient", receivedAt);
        var unrelatedFordMention = policy.extract(
                "Autorizo compartir mi correo con Toyota para cotizar un Ford: ana@example.com",
                "message-ford-vehicle-mention", receivedAt);
        var fordVehicleAfterOtherRecipient = policy.extract(
                "Autorizo compartir mi correo con Juan para Ford Mustang: ana@example.com",
                "message-ford-model-after-other-recipient", receivedAt);
        var namedThirdPartyContact = policy.extract(
                "Autorizo compartir mi correo con Ford, el de Pedro es pedro@example.com",
                "message-named-third-party-contact", receivedAt);

        assertThat(otherPerson.acceptedValues()).isEmpty();
        assertThat(restrictedRecipient.acceptedValues()).isEmpty();
        assertThat(unrelatedConsent.acceptedValues()).isEmpty();
        assertThat(familyContactAfterConsent.acceptedValues()).isEmpty();
        assertThat(explicitlyUnauthorizedFord.acceptedValues()).isEmpty();
        assertThat(unrelatedFordMention.acceptedValues()).isEmpty();
        assertThat(fordVehicleAfterOtherRecipient.acceptedValues()).isEmpty();
        assertThat(namedThirdPartyContact.acceptedValues()).isEmpty();
        assertThat(otherPerson.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(restrictedRecipient.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(unrelatedConsent.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(familyContactAfterConsent.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(explicitlyUnauthorizedFord.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(unrelatedFordMention.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(fordVehicleAfterOtherRecipient.sanitizedText()).doesNotContain("ana@example.com");
        assertThat(namedThirdPartyContact.sanitizedText()).doesNotContain("pedro@example.com");
    }

    @Test
    void doesNotAcceptAmbiguousUnprefixedForeignPhone() {
        var result = policy.extract("Autorizo compartir mi número con Ford: 1 (415) 555-2671",
                "message-6", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("1 (415) 555-2671");
    }

    @Test
    void rejectsMultipleContactValuesBecauseConsentCannotBeBoundToOneExactValue() {
        var result = policy.extract("Autorizo compartir mi correo y teléfono: ana@example.com, 55 1234 5678",
                "message-7", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("ana@example.com", "55 1234 5678")
                .contains("[dato de contacto omitido]");
    }

    @Test
    void doesNotApplyPhoneConsentToAnEmailValueInSameMessage() {
        var result = policy.extract(
                "Autorizo compartir el teléfono de mi hermana; mi correo es ana@example.com",
                "message-12", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("ana@example.com");
    }

    @Test
    void doesNotApplyConsentForAnotherPersonsPhoneToTheBuyersPhone() {
        var result = policy.extract(
                "Autorizo compartir el teléfono de mi hermana; mi número es 55 1234 5678",
                "message-13", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("55 1234 5678");
    }

    @Test
    void rejectsEmailWithOversizedLocalPartButStillRedactsIt() {
        var email = "a".repeat(65) + "@example.com";
        var result = policy.extract("Autorizo compartir mi correo con Ford: " + email,
                "message-9", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain(email);
    }

    @Test
    void redactsMalformedEmailLikeValueWithoutAcceptingIt() {
        var result = policy.extract("Mi correo ana@example está mal escrito", "message-10", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("ana@example");
    }

    @Test
    void rejectsMexicanPhoneWithInvalidLeadingZero() {
        var result = policy.extract("Autorizo compartir mi teléfono con Ford: 0123456789",
                "message-11", receivedAt);

        assertThat(result.acceptedValues()).isEmpty();
        assertThat(result.sanitizedText()).doesNotContain("0123456789");
    }
}
