package com.satyam.notificationworker;

import com.satyam.notificationworker.api.CreateNotificationRequest;
import com.satyam.notificationworker.api.CreateNotificationResponse;
import com.satyam.notificationworker.api.NotificationResponse;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.email.EmailSender;
import com.satyam.notificationworker.messaging.NotificationMessage;
import com.satyam.notificationworker.messaging.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, NotificationIntegrationTest.FailingEmailConfig.class})
class NotificationIntegrationTest {

    private static final AtomicInteger FAIL_MODE = new AtomicInteger(0);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CountingEmailSender countingEmailSender;

    @DynamicPropertySource
    static void notificationProps(DynamicPropertyRegistry registry) {
        registry.add("notification.max-attempts", () -> "3");
        registry.add("notification.email.provider", () -> "logging");
        registry.add("notification.retry.initial-backoff-ms", () -> "50");
        registry.add("notification.retry.multiplier", () -> "1");
        registry.add("notification.outbox.poll-interval-ms", () -> "50");
    }

    @Test
    void createNotificationEventuallyBecomesSent() {
        FAIL_MODE.set(0);
        countingEmailSender.reset();

        ResponseEntity<CreateNotificationResponse> created = restTemplate.postForEntity(
                "/notifications",
                new CreateNotificationRequest("user@example.com", "Hello", "Body"),
                CreateNotificationResponse.class
        );

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().status()).isEqualTo(NotificationStatus.PENDING);
        UUID id = created.getBody().id();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            NotificationResponse status = restTemplate.getForObject("/notifications/" + id, NotificationResponse.class);
            assertThat(status).isNotNull();
            assertThat(status.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(status.attempts()).isEqualTo(1);
            assertThat(status.sentAt()).isNotNull();
        });

        assertThat(countingEmailSender.getSendCount()).isEqualTo(1);
    }

    @Test
    void exhaustedFailuresMarkFailedAndLandOnDlq() {
        FAIL_MODE.set(1);
        countingEmailSender.reset();

        ResponseEntity<CreateNotificationResponse> created = restTemplate.postForEntity(
                "/notifications",
                new CreateNotificationRequest("fail@example.com", "Fail", "Body"),
                CreateNotificationResponse.class
        );
        assertThat(created.getBody()).isNotNull();
        UUID id = created.getBody().id();

        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            NotificationResponse status = restTemplate.getForObject("/notifications/" + id, NotificationResponse.class);
            assertThat(status).isNotNull();
            assertThat(status.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(status.attempts()).isEqualTo(3);
            assertThat(status.lastError()).isNotBlank();
        });

        Object dlqMessage = rabbitTemplate.receiveAndConvert(RabbitConfig.DLQ, 5_000);
        assertThat(dlqMessage).isInstanceOf(NotificationMessage.class);
        assertThat(((NotificationMessage) dlqMessage).notificationId()).isEqualTo(id);
        assertThat(countingEmailSender.getSendCount()).isEqualTo(3);
    }

    @Test
    void alreadySentIsIdempotentOnRedelivery() {
        FAIL_MODE.set(0);
        countingEmailSender.reset();

        ResponseEntity<CreateNotificationResponse> created = restTemplate.postForEntity(
                "/notifications",
                new CreateNotificationRequest("idem@example.com", "Idem", "Body"),
                CreateNotificationResponse.class
        );
        assertThat(created.getBody()).isNotNull();
        UUID id = created.getBody().id();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            NotificationResponse status = restTemplate.getForObject("/notifications/" + id, NotificationResponse.class);
            assertThat(status).isNotNull();
            assertThat(status.status()).isEqualTo(NotificationStatus.SENT);
        });

        int sendsAfterFirst = countingEmailSender.getSendCount();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, new NotificationMessage(id));

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(countingEmailSender.getSendCount()).isEqualTo(sendsAfterFirst)
        );
    }

    @Test
    void unknownIdReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/notifications/" + UUID.randomUUID(),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void invalidRequestReturns400() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/notifications",
                new CreateNotificationRequest("not-an-email", "", ""),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @TestConfiguration
    static class FailingEmailConfig {
        @Bean
        @Primary
        CountingEmailSender countingEmailSender() {
            return new CountingEmailSender();
        }
    }

    static class CountingEmailSender implements EmailSender {
        private final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void send(String to, String subject, String body) {
            sendCount.incrementAndGet();
            if (FAIL_MODE.get() == 1) {
                throw new RuntimeException("forced failure");
            }
        }

        int getSendCount() {
            return sendCount.get();
        }

        void reset() {
            sendCount.set(0);
        }
    }
}
