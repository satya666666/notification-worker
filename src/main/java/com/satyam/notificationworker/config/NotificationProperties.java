package com.satyam.notificationworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private int maxAttempts = 3;
    private final Email email = new Email();
    private final Retry retry = new Retry();
    private final Roles roles = new Roles();
    private final Outbox outbox = new Outbox();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Email getEmail() {
        return email;
    }

    public Retry getRetry() {
        return retry;
    }

    public Roles getRoles() {
        return roles;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    /**
     * Backoff after the given attempt number (already incremented).
     * Attempt 1 → initial; attempt 2 → initial * multiplier; capped at max.
     */
    public long retryBackoffMs(int attempts) {
        int exponent = Math.max(0, attempts - 1);
        double delay = retry.initialBackoffMs * Math.pow(retry.multiplier, exponent);
        return Math.min(retry.maxBackoffMs, Math.max(0, Math.round(delay)));
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

    public static class Retry {
        private long initialBackoffMs = 1000;
        private double multiplier = 2.0;
        private long maxBackoffMs = 30_000;

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }

    public static class Roles {
        private boolean api = true;
        private boolean worker = true;

        public boolean isApi() {
            return api;
        }

        public void setApi(boolean api) {
            this.api = api;
        }

        public boolean isWorker() {
            return worker;
        }

        public void setWorker(boolean worker) {
            this.worker = worker;
        }
    }

    public static class Outbox {
        private long pollIntervalMs = 200;
        private int batchSize = 50;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
