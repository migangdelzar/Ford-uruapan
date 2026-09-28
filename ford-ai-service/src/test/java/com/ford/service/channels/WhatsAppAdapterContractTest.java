package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppAdapterContractTest {
    @Test
    void normalizesTextMessage() {
        var message = new WhatsAppChannelAdapter(new ObjectMapper()).parseInbound(new RawWebhook(
                UUID.randomUUID(), """
                        {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-42"},"messages":[{"id":"wamid.1","from":"5214521234567","text":{"body":"¿Hay Ranger?"}}]}}]}]}
                        """, "{}"));
        assertThat(message).extracting(InboundMessage::channel, InboundMessage::providerMessageId, InboundMessage::text)
                .containsExactly(Channel.WHATSAPP, "wamid.1", "¿Hay Ranger?");
    }

    @Test
    void preservesImageAndAudioMediaReferencesWithoutDownloadingThem() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var messages = adapter.parseInboundMessages(webhook(message("wamid.image", "image",
                "\"image\":{\"id\":\"media-image-1\",\"mime_type\":\"image/jpeg\",\"caption\":\"¿Qué modelo es?\"}"),
                message("wamid.audio", "audio",
                        "\"audio\":{\"id\":\"media-audio-1\",\"mime_type\":\"audio/ogg\",\"voice\":true}")));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).kind()).isEqualTo(InboundMessageKind.IMAGE);
        assertThat(messages.get(0).text()).isEqualTo("¿Qué modelo es?");
        assertThat(messages.get(0).attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.providerMediaId()).isEqualTo("media-image-1");
            assertThat(attachment.declaredMimeType()).isEqualTo("image/jpeg");
        });
        assertThat(messages.get(1).kind()).isEqualTo(InboundMessageKind.AUDIO);
        assertThat(messages.get(1).attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.providerMediaId()).isEqualTo("media-audio-1");
            assertThat(attachment.declaredMimeType()).isEqualTo("audio/ogg");
        });
    }

    @Test
    void normalizesLocationAndInteractiveMessagesAsStructuredKinds() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var messages = adapter.parseInboundMessages(webhook(
                message("wamid.location", "location", "\"location\":{\"latitude\":19.4,\"longitude\":-102.0}"),
                message("wamid.interactive", "interactive", "\"interactive\":{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"offers\",\"title\":\"Promociones\"}}")));

        assertThat(messages.get(0).kind()).isEqualTo(InboundMessageKind.LOCATION);
        assertThat(messages.get(0).text()).contains("19.4", "-102.0");
        assertThat(messages.get(1).kind()).isEqualTo(InboundMessageKind.INTERACTIVE);
        assertThat(messages.get(1).text()).contains("offers", "Promociones");
    }

    @Test
    void preservesUnsupportedKindsForSafeClarificationAndRejectsMalformedIdentity() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var unsupported = adapter.parseInboundMessages(webhook(
                message("wamid.document", "document", "\"document\":{\"id\":\"document-1\",\"mime_type\":\"application/pdf\"}")));

        assertThat(unsupported.get(0).kind()).isEqualTo(InboundMessageKind.DOCUMENT);
        assertThat(unsupported.get(0).attachments()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.parseInboundMessages(webhook("{\"type\":\"image\",\"image\":{\"id\":\"media-1\"}}")))
                .withMessageContaining("provider message ID");
    }

    @Test
    void recognizesVideoStickerAndUnknownMessageKindsWithoutLosingTheirIdentity() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var messages = adapter.parseInboundMessages(webhook(
                message("wamid.video", "video", "\"video\":{\"id\":\"video-1\",\"mime_type\":\"video/mp4\"}"),
                message("wamid.sticker", "sticker", "\"sticker\":{\"id\":\"sticker-1\",\"mime_type\":\"image/webp\"}"),
                message("wamid.other", "reaction", "\"reaction\":{\"emoji\":\"👍\"}")));

        assertThat(messages).extracting(InboundMessage::kind)
                .containsExactly(InboundMessageKind.VIDEO, InboundMessageKind.STICKER, InboundMessageKind.UNSUPPORTED);
    }

    @Test
    void parsesMixedProviderAccountsInsideOneWebhookWithoutCrossingAccountBoundaries() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var payload = "{\"entry\":[{\"changes\":["
                + "{\"value\":{\"metadata\":{\"phone_number_id\":\"phone-42\"},\"messages\":["
                + message("wamid.1", "text", "\"text\":{\"body\":\"Hola\"}") + "]}},"
                + "{\"value\":{\"metadata\":{\"phone_number_id\":\"phone-99\"},\"messages\":["
                + message("wamid.2", "text", "\"text\":{\"body\":\"Hola\"}") + "]}}]}]}";

        var webhook = new RawWebhook(UUID.randomUUID(), payload, "{}");
        assertThat(adapter.providerAccountIds(payload)).containsExactly("phone-42", "phone-99");
        assertThat(adapter.parseInboundMessages(webhook, "phone-42"))
                .extracting(InboundMessage::providerMessageId).containsExactly("wamid.1");
        assertThat(adapter.parseInboundMessages(webhook, "phone-99"))
                .extracting(InboundMessage::providerMessageId).containsExactly("wamid.2");
    }

    @Test
    void rejectsMalformedTextAndIdentityAndIgnoresStatusOnlyPayloads() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.parseInboundMessages(webhook(
                        message("wamid.blank", "text", "\"text\":{\"body\":\" \"}"))))
                .withMessageContaining("text body");
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.parseInboundMessages(webhook(
                        "{\"id\":\"wamid.no-sender\",\"type\":\"text\",\"text\":{\"body\":\"Hola\"}}")))
                .withMessageContaining("participant ID");
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.parseInboundMessages(new RawWebhook(UUID.randomUUID(), "{", "{}")))
                .withMessageContaining("Invalid Meta webhook JSON");

        var status = new RawWebhook(UUID.randomUUID(),
                "{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"phone-42\"},\"statuses\":[{\"id\":\"wamid.1\"}]}}]}]}",
                "{}");
        assertThat(adapter.isStatusCallback(status.body())).isTrue();
        assertThat(adapter.parseInboundMessages(status)).isEmpty();
    }

    @Test
    void rejectsLegacySingleMessageParsingForAWebhookBatch() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.parseInbound(webhook(
                        message("wamid.1", "text", "\"text\":{\"body\":\"Hola\"}"),
                        message("wamid.2", "text", "\"text\":{\"body\":\"Buenas\"}"))))
                .withMessageContaining("use parseInboundMessages");
    }

    private static RawWebhook webhook(String... messages) {
        var payload = "{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"phone-42\"},\"messages\":["
                + String.join(",", messages) + "]}}]}]}";
        return new RawWebhook(UUID.randomUUID(), payload, "{}");
    }

    private static String message(String id, String type, String content) {
        return "{\"id\":\"" + id + "\",\"from\":\"5214521234567\",\"type\":\"" + type + "\"," + content + "}";
    }
}
