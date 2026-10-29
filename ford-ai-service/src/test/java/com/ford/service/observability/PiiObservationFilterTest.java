package com.ford.service.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiObservationFilterTest {

    @Test
    void removesSensitiveHighAndLowCardinalityAttributes() {
        var context = new Observation.Context()
                .addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", "customer phone 4521234567"))
                .addHighCardinalityKeyValue(KeyValue.of("ford.customer.email", "customer@example.com"))
                .addHighCardinalityKeyValue(KeyValue.of("ford.customer.name", "Ada Lovelace"))
                .addHighCardinalityKeyValue(KeyValue.of("ford.finance.amount_mxn", "450000"))
                .addHighCardinalityKeyValue(KeyValue.of("ford.finance.income", "50000"))
                .addHighCardinalityKeyValue(KeyValue.of("ford.customer.curp", "LOVA000000HDFXXX00"))
                .addHighCardinalityKeyValue(KeyValue.of("provider.id", "wamid.abc123"))
                .addLowCardinalityKeyValue(KeyValue.of("channel", "whatsapp"))
                .addLowCardinalityKeyValue(KeyValue.of("http.request.body", "raw payload"));

        new PiiObservationFilter().map(context);

        assertThat(context.getAllKeyValues().stream().map(KeyValue::getKey))
                .containsExactly("channel");
    }
}
