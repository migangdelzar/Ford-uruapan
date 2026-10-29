package com.ford.service.identity.application;

public interface OneTimeTokenEmailSender {
    void send(String recipient, String magicLink);
}
