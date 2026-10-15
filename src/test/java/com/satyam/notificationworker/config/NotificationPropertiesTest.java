package com.satyam.notificationworker.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPropertiesTest {

    @Test
    void retryBackoffGrowsExponentiallyAndCaps() {
        NotificationProperties properties = new NotificationProperties();
        properties.getRetry().setInitialBackoffMs(1000);
        properties.getRetry().setMultiplier(2.0);
        properties.getRetry().setMaxBackoffMs(3000);

        assertThat(properties.retryBackoffMs(1)).isEqualTo(1000);
        assertThat(properties.retryBackoffMs(2)).isEqualTo(2000);
        assertThat(properties.retryBackoffMs(3)).isEqualTo(3000);
    }
}
