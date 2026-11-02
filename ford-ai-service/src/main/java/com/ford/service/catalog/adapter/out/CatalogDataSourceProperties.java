package com.ford.service.catalog.adapter.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.catalog.datasource")
public class CatalogDataSourceProperties {

    private boolean toolsEnabled;
    private String host = "localhost";
    private int port = 5432;
    private String username = "ford_catalog_reader";
    private String password = "";
    private String secretOcid = "";
    private String embeddingModel = "bge-m3:latest";

    public void validate() {
        if (!toolsEnabled) {
            return;
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Ford catalog database host is required when catalog is enabled");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Ford catalog database port must be between 1 and 65535");
        }
        if (!"ford_catalog_reader".equals(username)) {
            throw new IllegalArgumentException("Ford catalog datasource username must be ford_catalog_reader");
        }
        if (secretOcid == null || secretOcid.isBlank()) {
            throw new IllegalArgumentException("Ford catalog database secret OCID is required when catalog is enabled");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Ford catalog database password must be resolved before enabling catalog tools");
        }
        if (embeddingModel == null || !embeddingModel.toLowerCase(java.util.Locale.ROOT).contains("bge-m3")) {
            throw new IllegalArgumentException("Ford catalog query embedding model must be BGE-M3 compatible");
        }
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port
                + "/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true";
    }

    public boolean toolsEnabled() { return toolsEnabled; }
    public void setToolsEnabled(boolean toolsEnabled) { this.toolsEnabled = toolsEnabled; }
    public String host() { return host; }
    public void setHost(String host) { this.host = host; }
    public int port() { return port; }
    public void setPort(int port) { this.port = port; }
    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String password() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String secretOcid() { return secretOcid; }
    public void setSecretOcid(String secretOcid) { this.secretOcid = secretOcid; }
    public String embeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
}
