package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.BindMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Dedicated local Ollama endpoint for BGE only; model files are reused read-only from the host cache. */
final class LocalBgeOllamaContainer extends GenericContainer<LocalBgeOllamaContainer> {
    private static final int OLLAMA_PORT = 11434;
    private static final Path MODEL_CACHE = Path.of(System.getProperty("user.home"), ".ollama", "models");
    private static final Path BGE_MANIFEST = MODEL_CACHE.resolve(
            "manifests/registry.ollama.ai/library/bge-m3/latest");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    LocalBgeOllamaContainer() {
        super(DockerImageName.parse("ollama/ollama:0.40.4"));
        if (!Files.isRegularFile(BGE_MANIFEST)) {
            throw new IllegalStateException("local bge-m3 model cache is missing");
        }
        withFileSystemBind(MODEL_CACHE.toString(), "/models", BindMode.READ_ONLY);
        withEnv("OLLAMA_MODELS", "/models");
        withExposedPorts(OLLAMA_PORT);
        withCommand("serve");
        waitingFor(Wait.forHttp("/api/tags").forPort(OLLAMA_PORT).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(OLLAMA_PORT);
    }

    boolean hasBgeModel() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            var models = json.readTree(response.body()).path("models");
            for (var model : models) {
                if (model.path("name").asText().startsWith("bge-m3")) return true;
            }
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException("could not verify bge-m3 in local embedding container", failure);
        }
    }
}
