package com.ford.service.architecture;

import com.ford.service.FordServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModuleDocumentationTest {

    @Test
    void writesModuleDiagramsAndCanvases() {
        ApplicationModules modules = ApplicationModules.of(FordServiceApplication.class);

        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases()
                .writeAggregatingDocument();
    }
}
