package com.ford.service.conversations.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationHoursBindingTest {

    @Test
    void defaultsMatchTheDealershipContract() {
        var hours = new ConversationProperties().hours();

        assertThat(hours.zone()).isEqualTo("America/Mexico_City");
        assertThat(hours.weekdayOpens()).isEqualTo(LocalTime.of(9, 0));
        assertThat(hours.weekdayCloses()).isEqualTo(LocalTime.of(19, 0));
        assertThat(hours.saturdayOpens()).isEqualTo(LocalTime.of(9, 0));
        assertThat(hours.saturdayCloses()).isEqualTo(LocalTime.of(14, 0));
        assertThat(hours.calendar()).isEmpty();
    }

    @Test
    void bindsZoneWindowsAndCalendarOverrides() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ford.conversations.hours.zone", "America/Mexico_City",
                "ford.conversations.hours.weekday-opens", "08:00",
                "ford.conversations.hours.weekday-closes", "20:00",
                "ford.conversations.hours.saturday-opens", "10:00",
                "ford.conversations.hours.saturday-closes", "13:00",
                "ford.conversations.hours.calendar.2026-12-25", "closed"));

        var properties = new Binder(source).bind("ford.conversations", ConversationProperties.class).get();

        assertThat(properties.hours().zone()).isEqualTo("America/Mexico_City");
        assertThat(properties.hours().weekdayOpens()).isEqualTo(LocalTime.of(8, 0));
        assertThat(properties.hours().weekdayCloses()).isEqualTo(LocalTime.of(20, 0));
        assertThat(properties.hours().saturdayOpens()).isEqualTo(LocalTime.of(10, 0));
        assertThat(properties.hours().saturdayCloses()).isEqualTo(LocalTime.of(13, 0));
        assertThat(properties.hours().calendar()).containsEntry("2026-12-25", "closed");
    }
}
