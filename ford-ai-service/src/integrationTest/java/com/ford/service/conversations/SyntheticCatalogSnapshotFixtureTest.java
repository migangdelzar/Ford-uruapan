package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.database.FordPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SyntheticCatalogSnapshotFixtureTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesCompleteManifestCheckedSnapshotUsingOnlySyntheticCatalogEvidence(@TempDir Path directory)
            throws Exception {
        var snapshot = SyntheticCatalogSnapshotFixture.write(directory);
        var manifest = objectMapper.readTree(snapshot.resolve("snapshot-manifest.json").toFile());

        assertThat(manifest.path("sourceLabel").asText()).isEqualTo("test-only synthetic CSV");
        assertThat(manifest.path("refreshedAt").asText()).isNotBlank();
        assertThat(manifest.path("files").size()).isEqualTo(LocalCatalogSnapshotLoader.FILE_NAMES.size());
        for (var name : LocalCatalogSnapshotLoader.FILE_NAMES) {
            var path = snapshot.resolve(name);
            assertThat(path).exists();
            var bytes = Files.readAllBytes(path);
            var entry = findEntry(manifest.path("files"), name);
            assertThat(entry.path("rowCount").asInt()).isGreaterThan(0);
            assertThat(entry.path("sha256").asText()).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(Files.readAllLines(path)).hasSize(entry.path("rowCount").asInt() + 1);
        }

        var joinedCsv = new StringBuilder();
        for (var name : LocalCatalogSnapshotLoader.FILE_NAMES) joinedCsv.append(read(snapshot.resolve(name)));
        assertThat(joinedCsv.toString()).contains("Ranger", "Maverick", "catalog.synthetic.invalid")
                .doesNotContain("ford.com", "forduruapan.mx", "customer", "lead");
    }

    @Test
    void generatedSnapshotImportsThroughStrictLoaderIntoDisposableCatalogDatabase(@TempDir Path directory)
            throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var snapshot = SyntheticCatalogSnapshotFixture.write(directory);

        var imported = LocalCatalogSnapshotLoader.load(snapshot, dataSource);

        assertThat(imported.rowCounts()).containsEntry("vehicle-search.csv", 2)
                .containsEntry("vehicle-variants.csv", 2)
                .containsEntry("vehicle-facts.csv", 2)
                .containsEntry("comparison-facts.csv", 1)
                .containsEntry("promotions.csv", 1)
                .containsEntry("product-vectors.csv", 2);
        var catalog = new com.ford.service.catalog.adapter.out.JdbcCatalogAdapter(
                JdbcClient.create(dataSource), objectMapper);
        assertThat(catalog.search(new com.ford.service.catalog.api.VehicleSearchQuery(
                "Ranger", "Ranger", 2027, null, null, null, null, null, 5)))
                .hasSize(1)
                .allSatisfy(vehicle -> {
                    assertThat(vehicle.model()).isEqualTo("Ranger");
                    assertThat(vehicle.price()).isEqualByComparingTo("907000");
                    assertThat(vehicle.priceSourceUrl()).contains("catalog.synthetic.invalid");
                });
    }

    private static com.fasterxml.jackson.databind.JsonNode findEntry(
            com.fasterxml.jackson.databind.JsonNode files, String name) {
        for (var entry : files) {
            if (name.equals(entry.path("name").asText())) return entry;
        }
        throw new AssertionError("Snapshot manifest lacks " + name);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception failure) {
            throw new AssertionError("Could not read synthetic fixture " + path.getFileName(), failure);
        }
    }
}
