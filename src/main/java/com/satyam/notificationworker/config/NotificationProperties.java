package com.satyam.notificationworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private int maxAttempts = 3;
    private final Email email = new Email();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Email getEmail() {
        return email;
    }

    public static class Email {
        /**
         * Active email provider: {@code logging} or {@code smtp}.
         */
        private String provider = "logging";
        private String from = "noreply@example.com";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }
    }
}
