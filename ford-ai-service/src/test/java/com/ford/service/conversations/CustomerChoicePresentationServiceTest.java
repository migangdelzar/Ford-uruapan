package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.conversations.application.CustomerChoicePresentationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerChoicePresentationServiceTest {
    private final CustomerChoicePresentationService presenter = new CustomerChoicePresentationService();
    private final List<VehicleNameIndexEntry> entries = List.of(
            new VehicleNameIndexEntry("Ranger", 2027, "XL Gasolina 4x2"),
            new VehicleNameIndexEntry("Ranger", 2027, "XLT Gasolina 4x2"),
            new VehicleNameIndexEntry("Ranger", 2027, "XLT Diésel 4x4"),
            new VehicleNameIndexEntry("Ranger", 2027, "Wildtrak Diésel 4x4"));

    @Test
    void returnsOnlyExactNamesInAnswerOrderAndCapsAtThree() {
        var answer = "Ranger 2027 XLT Diésel 4x4; Ranger 2027 XL Gasolina 4x2; "
                + "Ranger 2027 XLT Gasolina 4x2; Ranger 2027 Wildtrak Diésel 4x4";

        assertThat(presenter.fromAnswer(answer, entries))
                .extracting(ReplyOption::title)
                .containsExactly("Ranger 2027 XLT Diésel 4x4", "Ranger 2027 XL Gasolina 4x2",
                        "Ranger 2027 XLT Gasolina 4x2");
    }

    @Test
    void doesNotCreateChoicesFromPartialNames() {
        assertThat(presenter.fromAnswer("Puede elegir XLT", entries)).isEmpty();
    }

    @Test
    void createsStableIdentityOnlyReplyIds() {
        var option = presenter.fromAnswer("Ranger 2027 XLT Gasolina 4x2", entries).getFirst();

        assertThat(option.id()).matches("vehicle-[a-f0-9]{16}");
        assertThat(presenter.fromAnswer("Ranger 2027 XLT Gasolina 4x2", entries).getFirst().id())
                .isEqualTo(option.id());
        assertThat(option.title()).isEqualTo("Ranger 2027 XLT Gasolina 4x2");
    }

    @Test
    void resolvesOnlyCurrentChoiceIdAndMatchingTitle() {
        var option = presenter.fromAnswer("Ranger 2027 XLT Gasolina 4x2", entries).getFirst();

        assertThat(presenter.resolveSelection("Opción elegida: " + option.title() + " (" + option.id() + ")", entries))
                .contains(entries.get(1));
        assertThat(presenter.resolveSelection("Opción elegida: Otra versión (" + option.id() + ")", entries))
                .isEmpty();
        assertThat(presenter.resolveSelection("Opción elegida: " + option.title() + " (vehicle-0000000000000000)", entries))
                .isEmpty();
        assertThat(presenter.resolveSelection("Opción elegida: " + option.title() + " (" + option.id() + ")",
                entries.subList(0, 1))).isEmpty();
    }
}
