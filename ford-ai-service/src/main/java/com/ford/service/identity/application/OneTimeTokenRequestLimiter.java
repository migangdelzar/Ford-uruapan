package com.ford.service.identity.application;

public interface OneTimeTokenRequestLimiter {
    boolean allow(String normalizedEmail);
}
