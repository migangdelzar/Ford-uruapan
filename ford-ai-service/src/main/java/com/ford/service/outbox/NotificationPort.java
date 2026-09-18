package com.ford.service.outbox;

public interface NotificationPort {
    NotificationResult notify(Notification notification);
}
