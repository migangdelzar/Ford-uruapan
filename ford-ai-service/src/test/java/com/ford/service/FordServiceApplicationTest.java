package com.ford.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "ford.outbox.enabled=true",
        "ford.channels.meta.delivery.enabled=true",
        "ford.channels.meta.delivery.whatsapp-endpoint=http://localhost:18080/messages",
        "ford.channels.meta.delivery.whatsapp-access-token=test-only-token"
})
@ActiveProfiles("test")
class FordServiceApplicationTest {

    @Test
    void startsWithTheFordServiceApplicationContext() {
    }
}
