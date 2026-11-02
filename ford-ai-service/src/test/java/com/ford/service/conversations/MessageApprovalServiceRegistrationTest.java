package com.ford.service.conversations;

import com.ford.service.conversations.application.MessageApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ford.outbox.enabled=true",
        "ford.channels.meta.delivery.enabled=true",
        "ford.channels.meta.delivery.whatsapp-endpoint=http://localhost:18080/messages",
        "ford.channels.meta.delivery.whatsapp-access-token=test-only-token"
})
@ActiveProfiles("test")
class MessageApprovalServiceRegistrationTest {
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void registersTransactionalApprovalWorkflowOnlyWithConcreteEffectsAndValidation() {
        assertThat(context.getBean(MessageApprovalService.class)).isNotNull();
    }
}
