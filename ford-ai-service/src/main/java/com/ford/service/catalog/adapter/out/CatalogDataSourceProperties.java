package com.ford.service.catalog.adapter.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.catalog.datasource")
public class CatalogDataSourceProperties {

    private String url = "";
    private String username = "";
    private String password = "";
    private String driverClassName = "org.postgresql.Driver";

    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String password() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String driverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    void validate() {
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Local Ford catalog tools require a dedicated read-only catalog JDBC URL and credentials");
        }
    }
}
