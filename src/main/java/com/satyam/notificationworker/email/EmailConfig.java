package com.satyam.notificationworker.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Marker config documenting the email provider switch.
 * Beans are selected via {@code @ConditionalOnProperty} on
 * {@link LoggingEmailSender} and {@link SmtpEmailSender}.
 */
@Configuration
@ConditionalOnProperty(name = "notification.email.provider")
public class EmailConfig {
}
