package com.satyam.notificationworker.email;

public interface EmailSender {

    void send(String to, String subject, String body);
}
