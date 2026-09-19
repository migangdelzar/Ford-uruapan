package com.ford.service.channels.adapter.out.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.channels.meta.delivery")
public class MetaDeliveryProperties {
    private boolean enabled;
    private String whatsappEndpoint = "";
    private String messengerEndpoint = "";
    private String instagramEndpoint = "";
    private String whatsappAccessToken = "";
    private String messengerAccessToken = "";
    private String instagramAccessToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWhatsappEndpoint() {
        return whatsappEndpoint;
    }

    public void setWhatsappEndpoint(String whatsappEndpoint) {
        this.whatsappEndpoint = whatsappEndpoint;
    }

    public String getMessengerEndpoint() {
        return messengerEndpoint;
    }

    public void setMessengerEndpoint(String messengerEndpoint) {
        this.messengerEndpoint = messengerEndpoint;
    }

    public String getInstagramEndpoint() {
        return instagramEndpoint;
    }

    public void setInstagramEndpoint(String instagramEndpoint) {
        this.instagramEndpoint = instagramEndpoint;
    }

    public String getWhatsappAccessToken() {
        return whatsappAccessToken;
    }

    public void setWhatsappAccessToken(String whatsappAccessToken) {
        this.whatsappAccessToken = whatsappAccessToken;
    }

    public String getMessengerAccessToken() {
        return messengerAccessToken;
    }

    public void setMessengerAccessToken(String messengerAccessToken) {
        this.messengerAccessToken = messengerAccessToken;
    }

    public String getInstagramAccessToken() {
        return instagramAccessToken;
    }

    public void setInstagramAccessToken(String instagramAccessToken) {
        this.instagramAccessToken = instagramAccessToken;
    }
}
