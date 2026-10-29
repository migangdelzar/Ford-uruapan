package com.ford.service.ai;

import com.ford.service.ai.application.EvidenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EvidenceResultTest {
    @Test
    void distinguishesSupportedUnknownAndConflictedEvidence() {
        assertThat(EvidenceResult.supported(List.of("catalog:vehicle-fact"), 1).status())
                .isEqualTo(EvidenceResult.Status.SUPPORTED);
        assertThat(EvidenceResult.unknown().status()).isEqualTo(EvidenceResult.Status.UNKNOWN);
        assertThat(EvidenceResult.conflicted(List.of("catalog:source-a", "catalog:source-b"), 2).status())
                .isEqualTo(EvidenceResult.Status.CONFLICTED);
    }

    @Test
    void rejectsClaimOfSupportWithoutEvidenceReferences() {
        assertThatIllegalArgumentException().isThrownBy(() -> EvidenceResult.supported(List.of(), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> EvidenceResult.conflicted(List.of("source-a"), 1));
    }
}
