package com.ford.service.team.api;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonValue;

public record AdvisorView(UUID id, String name, String email, String role, String branch,
                          Status status, String avatarUrl, long assignedCount) {
    public enum Status {
        AVAILABLE("available"), IN_MEETING("in_meeting"), AWAY("away");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
