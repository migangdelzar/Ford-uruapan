package com.ford.service.catalog.adapter.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVehicleCatalogRepositoryTest {

    @Test
    void typesNullableSearchParametersForPostgres() {
        assertThat(JdbcVehicleCatalogRepository.searchSql())
                .contains("cast(:text as text) is null")
                .contains("cast(:modelYear as integer) is null");
    }
}
