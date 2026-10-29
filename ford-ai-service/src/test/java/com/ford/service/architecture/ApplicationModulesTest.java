package com.ford.service.architecture;

import com.ford.service.FordServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesTest {

    @Test
    void applicationModulesHaveNoCyclesOrInternalAccessViolations() {
        ApplicationModules.of(FordServiceApplication.class).verify();
    }
}
