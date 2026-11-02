package com.ford.service.handoffcontact.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ford.alternate-contact.deletion-journal")
public class AlternateContactDeletionJournalProperties {
    private boolean enabled;
    private String namespace;
    private String bucket;
    private String prefix = "ford/contact-deletions";
    private String region;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String value) { namespace = value; }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String value) { prefix = value; }
    public String getRegion() { return region; }
    public void setRegion(String value) { region = value; }

    public boolean isConfigured() {
        return enabled && present(namespace) && present(bucket) && present(prefix);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
