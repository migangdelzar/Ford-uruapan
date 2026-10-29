package com.ford.service.ai;

import com.ford.service.ai.application.CatalogVehicleResolver;
import com.ford.service.ai.application.VehicleEntityResolver;
import com.ford.service.ai.application.CatalogVehicleResolution;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogVehicleResolverTest {
    private static final List<VehicleNameIndexEntry> ENTRIES = List.of(
            new VehicleNameIndexEntry("RANGER", 2027, "XLT Gasolina 4x2"),
            new VehicleNameIndexEntry("RANGER", 2027, "XLT Diésel 4x4"),
            new VehicleNameIndexEntry("RANGER", 2027, "XL Gasolina 4x2"));
    private final CatalogVehicleResolver resolver = new CatalogVehicleResolver();
    private final VehicleEntityResolver extractor = new VehicleEntityResolver();

    @Test
    void returnsBothCanonicalChoicesWhenBuyerNamesOnlyAmbiguousTrimFamily() {
        var mention = extractor.resolve("¿Tienes una medida de caja de la Ranger XLT 2027?", null);

        var result = resolver.resolve(mention, ENTRIES);

        assertThat(result.status()).isEqualTo(CatalogVehicleResolution.Status.AMBIGUOUS);
        assertThat(result.exact()).isNull();
        assertThat(result.choices()).containsExactly(
                new com.ford.service.ai.application.VehicleTrimCandidate("RANGER", 2027, "XLT Diésel 4x4"),
                new com.ford.service.ai.application.VehicleTrimCandidate("RANGER", 2027, "XLT Gasolina 4x2"));
    }

    @Test
    void resolvesOnlyAUniqueCanonicalTrimAndNeverChoosesByPrefixAlone() {
        var mention = extractor.resolve("Ranger 2027 XLT Gasolina 4x2", null);

        var result = resolver.resolve(mention, ENTRIES);

        assertThat(result.status()).isEqualTo(CatalogVehicleResolution.Status.EXACT);
        assertThat(result.exact()).isEqualTo(
                new com.ford.service.ai.application.VehicleTrimCandidate("RANGER", 2027, "XLT Gasolina 4x2"));
        assertThat(result.choices()).isEmpty();
    }

    @Test
    void resolvesUniqueWholeTokenPrefixToItsCanonicalTrim() {
        var result = resolver.resolve("RANGER", 2027, "XL", ENTRIES);

        assertThat(result.status().name()).isEqualTo("UNIQUE_CANONICAL_MATCH");
        assertThat(result.exact()).isEqualTo(
                new com.ford.service.ai.application.VehicleTrimCandidate("RANGER", 2027, "XL Gasolina 4x2"));
        assertThat(result.choices()).isEmpty();
    }

    @Test
    void nonMatchingTrimPrefixStaysMissing() {
        var result = resolver.resolve("RANGER", 2027, "XLE", ENTRIES);

        assertThat(result.status()).isEqualTo(CatalogVehicleResolution.Status.MISSING);
        assertThat(result.exact()).isNull();
        assertThat(result.choices()).isEmpty();
    }

    @Test
    void missingCatalogIdentityStaysMissing() {
        var mention = extractor.resolve("Ranger XLT 2028", null);

        var result = resolver.resolve(mention, ENTRIES);

        assertThat(result.status()).isEqualTo(CatalogVehicleResolution.Status.MISSING);
        assertThat(result.exact()).isNull();
        assertThat(result.choices()).isEmpty();
    }
}
