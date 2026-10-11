package com.ford.service.architecture;

import com.ford.service.catalog.application.VehicleCatalog;
import com.ford.service.finance.application.DeterministicFinanceCalculator;
import com.ford.service.finance.application.FinanceOperations;
import com.ford.service.finance.application.FinanceService;
import com.ford.service.knowledge.application.KnowledgeIngestion;
import com.ford.service.knowledge.application.KnowledgeIngestionService;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import com.ford.service.knowledge.application.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleApiBoundaryTest {

    @Test
    void applicationImplementationPackagesAreNotExportedAsWholePackages() {
        assertThat(VehicleCatalog.class.getPackage().getAnnotation(NamedInterface.class)).isNull();
        assertThat(DeterministicFinanceCalculator.class.getPackage().getAnnotation(NamedInterface.class)).isNull();
        assertThat(KnowledgeRetrievalService.class.getPackage().getAnnotation(NamedInterface.class)).isNull();
    }

    @Test
    void catalogPublicContractUsesATypeLevelNamedInterface() {
        assertThat(VehicleCatalog.class.isAnnotationPresent(NamedInterface.class)).isTrue();
    }

    @Test
    void financeAndKnowledgePublicContractsUseTypeLevelNamedInterfaces() {
        assertThat(FinanceOperations.class.isAnnotationPresent(NamedInterface.class)).isTrue();
        assertThat(KnowledgeIngestion.class.isAnnotationPresent(NamedInterface.class)).isTrue();
        assertThat(KnowledgeRetrieval.class.isAnnotationPresent(NamedInterface.class)).isTrue();
        assertThat(FinanceService.class.isAnnotationPresent(NamedInterface.class)).isFalse();
        assertThat(KnowledgeIngestionService.class.isAnnotationPresent(NamedInterface.class)).isFalse();
        assertThat(KnowledgeRetrievalService.class.isAnnotationPresent(NamedInterface.class)).isFalse();
    }

    @Test
    void aiCompletionPortIsAnExplicitCrossModuleContract() {
        assertThat(com.ford.service.ai.application.port.out.ChatCompletionPort.class
                .getPackage().getAnnotation(NamedInterface.class)).isNotNull();
    }
}
