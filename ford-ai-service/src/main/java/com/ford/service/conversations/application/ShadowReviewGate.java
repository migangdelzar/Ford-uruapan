package com.ford.service.conversations.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Server-side gate for MÍA shadow evaluation.
 *
 * <p>When shadow review is enabled, MÍA drafts are persisted for operators but
 * never sent. The kill switch overrides shadow review and stops new bot sends
 * while leaving transcripts and journey data untouched.</p>
 */
@Component
public class ShadowReviewGate {

    private final boolean shadowEnabled;
    private final boolean killSwitchEnabled;

    @Autowired
    public ShadowReviewGate(ConversationProperties properties) {
        this(properties.shadowReviewEnabled(), properties.shadowReviewKillSwitch());
    }

    private ShadowReviewGate(boolean shadowEnabled, boolean killSwitchEnabled) {
        this.shadowEnabled = shadowEnabled;
        this.killSwitchEnabled = killSwitchEnabled;
    }

    public static ShadowReviewGate of(boolean shadowEnabled, boolean killSwitchEnabled) {
        return new ShadowReviewGate(shadowEnabled, killSwitchEnabled);
    }

    public static ShadowReviewGate disabled() {
        return new ShadowReviewGate(false, false);
    }

    /** Drafts are recorded, but only when the kill switch is not engaged. */
    public boolean shadowEnabled() {
        return shadowEnabled && !killSwitchEnabled;
    }

    public boolean killSwitchEnabled() {
        return killSwitchEnabled;
    }
}
