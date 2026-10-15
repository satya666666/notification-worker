package com.satyam.notificationworker.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "notifications";
    public static final String DLX = "notifications.dlx";
    public static final String QUEUE = "notifications.send";
    public static final String DLQ = "notifications.send.dlq";
    public static final String ROUTING_KEY = "send";
    public static final String DLQ_ROUTING_KEY = "send.dlq";

    public static final String RETRY_EXCHANGE = "notifications.retry";
    public static final String RETRY_QUEUE = "notifications.send.retry";
    public static final String RETRY_ROUTING_KEY = "send.retry";

    @Bean
    DirectExchange notificationsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    DirectExchange notificationsDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    DirectExchange notificationsRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    Queue notificationsSendQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue notificationsSendDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Queue notificationsRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    Binding notificationsSendBinding(Queue notificationsSendQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(notificationsSendQueue).to(notificationsExchange).with(ROUTING_KEY);
    }

    @Bean
    Binding notificationsDlqBinding(Queue notificationsSendDlq, DirectExchange notificationsDlx) {
        return BindingBuilder.bind(notificationsSendDlq).to(notificationsDlx).with(DLQ_ROUTING_KEY);
    }

    @Bean
    Binding notificationsRetryBinding(Queue notificationsRetryQueue, DirectExchange notificationsRetryExchange) {
        return BindingBuilder.bind(notificationsRetryQueue).to(notificationsRetryExchange).with(RETRY_ROUTING_KEY);
    }

    @Bean
    MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
