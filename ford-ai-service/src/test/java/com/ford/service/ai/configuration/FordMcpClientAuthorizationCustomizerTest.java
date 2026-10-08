package com.ford.service.ai.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FordMcpClientAuthorizationCustomizerTest {

    @Test
    void sendsConfiguredBearerTokenOnStreamableHttpInitialization() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        var authorizationHeader = new AtomicReference<String>();
        server.createContext("/mcp", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = mapper.readTree(exchange.getRequestBody());
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            var result = response.putObject("result");
            result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
            result.putObject("capabilities");
            result.putObject("serverInfo").put("name", "test-mcp").put("version", "1.0");
            var body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-only-mcp-token");
        var transportBuilder = HttpClientStreamableHttpTransport.builder(
                        "http://127.0.0.1:" + server.getAddress().getPort())
                .endpoint("/mcp");
        new FordMcpClientAuthorizationCustomizer(properties).customize("ford-uruapan", transportBuilder);

        try (McpSyncClient client = McpClient.sync(transportBuilder.build()).build()) {
            client.initialize();
        } finally {
            server.stop(0);
        }

        assertThat(authorizationHeader.get()).isEqualTo("Bearer test-only-mcp-token");
    }

    @Test
    void doesNotAddFordBearerTokenToOtherMcpConnections() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        var authorizationHeader = new AtomicReference<String>();
        server.createContext("/mcp", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = mapper.readTree(exchange.getRequestBody());
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            var result = response.putObject("result");
            result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
            result.putObject("capabilities");
            result.putObject("serverInfo").put("name", "test-mcp").put("version", "1.0");
            var body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-only-mcp-token");
        var transportBuilder = HttpClientStreamableHttpTransport.builder(
                        "http://127.0.0.1:" + server.getAddress().getPort())
                .endpoint("/mcp");
        new FordMcpClientAuthorizationCustomizer(properties).customize("other-connection", transportBuilder);

        try (McpSyncClient client = McpClient.sync(transportBuilder.build()).build()) {
            client.initialize();
        } finally {
            server.stop(0);
        }

        assertThat(authorizationHeader.get()).isNull();
    }

    @Test
    void addsCloudflareServiceAuthHeadersAlongsideMcpBearerToken() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        var headers = new java.util.concurrent.ConcurrentHashMap<String, String>();
        server.createContext("/mcp", exchange -> {
            headers.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestHeaders().getFirst("CF-Access-Client-Id") != null) {
                headers.put("access-id", exchange.getRequestHeaders().getFirst("CF-Access-Client-Id"));
            }
            if (exchange.getRequestHeaders().getFirst("CF-Access-Client-Secret") != null) {
                headers.put("access-secret", exchange.getRequestHeaders().getFirst("CF-Access-Client-Secret"));
            }
            var request = mapper.readTree(exchange.getRequestBody());
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            var result = response.putObject("result");
            result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
            result.putObject("capabilities");
            result.putObject("serverInfo").put("name", "test-mcp").put("version", "1.0");
            var body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-only-mcp-token");
        properties.setAccessClientId("test-access-id");
        properties.setAccessClientSecret("test-access-secret");
        var transportBuilder = HttpClientStreamableHttpTransport.builder(
                        "http://127.0.0.1:" + server.getAddress().getPort())
                .endpoint("/mcp");
        new FordMcpClientAuthorizationCustomizer(properties).customize("ford-uruapan", transportBuilder);

        try (McpSyncClient client = McpClient.sync(transportBuilder.build()).build()) {
            client.initialize();
        } finally {
            server.stop(0);
        }

        assertThat(headers).containsEntry("authorization", "Bearer test-only-mcp-token")
                .containsEntry("access-id", "test-access-id")
                .containsEntry("access-secret", "test-access-secret");
    }

    @Test
    void doesNotAddAccessCredentialsToOtherMcpConnections() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        var accessId = new AtomicReference<String>();
        server.createContext("/mcp", exchange -> {
            if (exchange.getRequestHeaders().getFirst("CF-Access-Client-Id") != null) {
                accessId.set(exchange.getRequestHeaders().getFirst("CF-Access-Client-Id"));
            }
            var request = mapper.readTree(exchange.getRequestBody());
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            var result = response.putObject("result");
            result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
            result.putObject("capabilities");
            result.putObject("serverInfo").put("name", "test-mcp").put("version", "1.0");
            var body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-only-mcp-token");
        properties.setAccessClientId("test-access-id");
        properties.setAccessClientSecret("test-access-secret");
        var transportBuilder = HttpClientStreamableHttpTransport.builder(
                        "http://127.0.0.1:" + server.getAddress().getPort())
                .endpoint("/mcp");
        new FordMcpClientAuthorizationCustomizer(properties).customize("other-connection", transportBuilder);

        try (McpSyncClient client = McpClient.sync(transportBuilder.build()).build()) {
            client.initialize();
        } finally {
            server.stop(0);
        }

        assertThat(accessId.get()).isNull();
    }
}
