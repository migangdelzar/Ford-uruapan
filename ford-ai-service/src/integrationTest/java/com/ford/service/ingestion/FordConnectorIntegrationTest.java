package com.ford.service.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.adapter.out.ford.FordApiCatalogAdapter;
import com.ford.service.ingestion.domain.SyncRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FordConnectorIntegrationTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/catalog", exchange -> {
            var response = "[{\"id\":\"mustang-2026\",\"name\":\"Mustang\"}]";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsOfficialCatalogThroughRestClientAndPreservesSourceMetadata() {
        var client = RestClient.builder().baseUrl("http://localhost:" + server.getAddress().getPort()).build();
        var adapter = new FordApiCatalogAdapter(client, "/catalog", new ObjectMapper());

        var snapshot = adapter.fetchCatalog(new SyncRequest(UUID.randomUUID(), Instant.parse("2026-09-18T15:00:00Z")));

        assertThat(snapshot.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.metadata().sourceSystem()).isEqualTo("ford-api-catalog");
            assertThat(candidate.metadata().sourceRecordId()).isEqualTo("mustang-2026");
            assertThat(candidate.payload()).contains("Mustang");
        });
    }
}
