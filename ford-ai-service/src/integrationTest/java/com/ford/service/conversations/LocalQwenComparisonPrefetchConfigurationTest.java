package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalQwenComparisonPrefetchConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FixtureConfiguration.class, LocalQwenComparisonPrefetchConfiguration.class);

    @Test
    void rawModeLeavesChatCompletionPortUnwrapped() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatCompletionPort.class);
            assertThat(context.getBean(ChatCompletionPort.class))
                    .isNotInstanceOf(ReplayPrefetchingChatCompletionPort.class);
            assertThat(context).doesNotHaveBean(LocalReplayComparisonPrefetcher.class);
            assertThat(context).doesNotHaveBean(LocalReplayVehicleFactPrefetcher.class);
            assertThat(context).doesNotHaveBean(LocalReplayPromotionPrefetcher.class);
        });
    }

    @Test
    void optInModeWrapsChatCompletionPortExactlyOnce() {
        contextRunner.withUserConfiguration(IndexFixtureConfiguration.class)
                .withPropertyValues("ford.test.local-qwen-comparison-prefetch.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatCompletionPort.class);
                    var port = context.getBean(ChatCompletionPort.class);
                    assertThat(port).isInstanceOf(ReplayPrefetchingChatCompletionPort.class);
                    assertThat(context.getBeansOfType(ReplayPrefetchingChatCompletionPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(LocalReplayVehicleFactPrefetcher.class)).hasSize(1);
                    assertThat(context.getBeansOfType(LocalReplayPromotionPrefetcher.class)).hasSize(1);
                });
    }

    @Test
    void optInModeBuildsReplayOnlyNameIndexWhenApplicationHasNoConfiguredNameIndex() {
        contextRunner.withPropertyValues("ford.test.local-qwen-comparison-prefetch.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalReplayComparisonPrefetcher.class);
                    assertThat(context).hasSingleBean(LocalReplayVehicleFactPrefetcher.class);
                    assertThat(context).hasSingleBean(ReplayPrefetchingChatCompletionPort.class);
                });
    }

    @Test
    void usesNamesOnlyCatalogFallbackWhenPromptIndexIsEmpty() {
        contextRunner.withUserConfiguration(IndexFixtureConfiguration.class)
                .withPropertyValues("ford.test.local-qwen-comparison-prefetch.enabled=true")
                .run(context -> {
                    var names = context.getBean(CatalogNameIndexService.class);
                    var catalog = context.getBean(CatalogPort.class);
                    var tools = context.getBean(CatalogTools.class);
                    var pair = List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2");
                    var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, pair.get(0)),
                            new VehicleNameIndexEntry("RANGER", 2027, pair.get(1)));
                    when(names.currentEntries()).thenReturn(List.of());
                    when(catalog.nameIndexEntries()).thenReturn(entries);
                    when(tools.compareTrims("RANGER", 2027, pair, "summary")).thenReturn(comparison());

                    var result = context.getBean(LocalReplayComparisonPrefetcher.class).prefetch(
                            "De las dos versiones, Ranger 2027 XL Gasolina 4x2 y Ranger 2027 XLT Gasolina 4x2, "
                                    + "¿cuál tiene el precio publicado menor?");

                    assertThat(result).isPresent();
                    verify(catalog).nameIndexEntries();
                    verify(tools).compareTrims("RANGER", 2027, pair, "summary");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean ChatCompletionPort chatCompletionPort() {
            return (request, context) -> null;
        }

        @Bean CatalogTools catalogTools() {
            return mock(CatalogTools.class);
        }

        @Bean CatalogPort catalogPort() {
            return mock(CatalogPort.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IndexFixtureConfiguration {
        @Bean CatalogNameIndexService catalogNameIndexService() {
            return mock(CatalogNameIndexService.class);
        }
    }

    private static CatalogTools.TrimComparison comparison() {
        return new CatalogTools.TrimComparison("RANGER", 2027, null, null,
                List.of(new CatalogTools.TrimResult("XL GASOLINA 4X2", BigDecimal.valueOf(775_000), "MXN",
                                null, null),
                        new CatalogTools.TrimResult("XLT GASOLINA 4X2", BigDecimal.valueOf(907_000), "MXN",
                                null, null)),
                List.of(new CatalogTools.TrimPairResult("XL GASOLINA 4X2", "XLT GASOLINA 4X2",
                        "source_backed_differences_found", BigDecimal.valueOf(132_000), List.of(), null)),
                List.of(), null);
    }
}
