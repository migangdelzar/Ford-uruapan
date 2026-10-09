package com.ford.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "ford.security.auth")
public class AuthenticationProperties {

    private boolean enabled;
    private String issuerUri = "https://ford.emme.business";
    private String audience = "ford-ai-service";
    private String relyingPartyId = "ford.emme.business";
    private String relyingPartyName = "Ford Uruapan";
    private Set<String> allowedOrigins = Set.of("https://ford.emme.business");
    private String bffClientId = "ford-web-bff";
    private String bffClientSecret = "";
    private String bffRedirectUri = "https://ford.emme.business/api/auth/callback";
    private String signingKeyPkcs8Base64 = "";
    private String publicBaseUrl = "https://ford.emme.business";
    private String emailApiKey = "";
    private String emailFrom = "";
    private boolean csrfCookieSecure;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String issuerUri() { return issuerUri; }
    public void setIssuerUri(String value) { issuerUri = value; }
    public String audience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public String relyingPartyId() { return relyingPartyId; }
    public void setRelyingPartyId(String value) { relyingPartyId = value; }
    public String relyingPartyName() { return relyingPartyName; }
    public void setRelyingPartyName(String value) { relyingPartyName = value; }
    public Set<String> allowedOrigins() { return Set.copyOf(allowedOrigins); }
    public void setAllowedOrigins(Set<String> value) { allowedOrigins = Set.copyOf(value); }
    public String bffClientId() { return bffClientId; }
    public void setBffClientId(String value) { bffClientId = value; }
    public String bffClientSecret() { return bffClientSecret; }
    public void setBffClientSecret(String value) { bffClientSecret = value; }
    public String bffRedirectUri() { return bffRedirectUri; }
    public void setBffRedirectUri(String value) { bffRedirectUri = value; }
    public String signingKeyPkcs8Base64() { return signingKeyPkcs8Base64; }
    public void setSigningKeyPkcs8Base64(String value) { signingKeyPkcs8Base64 = value; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    public String emailApiKey() { return emailApiKey; }
    public void setEmailApiKey(String value) { emailApiKey = value; }
    public String emailFrom() { return emailFrom; }
    public void setEmailFrom(String value) { emailFrom = value; }
    public boolean csrfCookieSecure() { return csrfCookieSecure; }
    public void setCsrfCookieSecure(boolean value) { csrfCookieSecure = value; }
}
