package com.yash.Notifyr.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${notification.queue.name}")
    private String queueName;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    // for retries
    @Value("${notification.retry.exchange}")
    private String retryExchangeName;

    @Value("${notification.retry.queue-30s}")
    private String retryQueue30s;

    @Value("${notification.retry.queue-2m}")
    private String retryQueue2m;

    @Value("${notification.retry.queue-5m}")
    private String retryQueue5m;

    @Value("${notification.retry.routing-key-30s}")
    private String retryRoutingKey30s;

    @Value("${notification.retry.routing-key-2m}")
    private String retryRoutingKey2m;

    @Value("${notification.retry.routing-key-5m}")
    private String retryRoutingKey5m;

    // for dlq
    @Value("${notification.dlq.exchange}")
    private String dlqExchangeName;

    @Value("${notification.dlq.queue}")
    private String dlqQueueName;

    @Value("${notification.dlq.routing-key}")
    private String dlqRoutingKey;

    @Value("${notification.retry.ttl-30s}")
    private long ttl30s;

    @Value("${notification.retry.ttl-2m}")
    private long ttl2m;

    @Value("${notification.retry.ttl-5m}")
    private long ttl5m;

    @Value("${notification.campaign-dispatch.exchange}")
    private String campaignDispatchExchangeName;

    @Value("${notification.campaign-dispatch.queue}")
    private String campaignDispatchQueueName;

    @Value("${notification.campaign-dispatch.routing-key}")
    private String campaignDispatchRoutingKey;


    @Bean
    public Queue notificationQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(exchangeName);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder
                .bind(notificationQueue)
                .to(notificationExchange)
                .with(routingKey);
    }

    // retry exchange
    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(retryExchangeName);
    }

    @Bean
    public Queue retryQueue30s() {  // 30 seconds
        return QueueBuilder.durable(retryQueue30s)
                .withArgument("x-message-ttl", ttl30s)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding30s(Queue retryQueue30s, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue30s).to(retryExchange).with(retryRoutingKey30s);
    }

    @Bean
    public Queue retryQueue2m() {   // 2 mins
        return QueueBuilder.durable(retryQueue2m)
                .withArgument("x-message-ttl", ttl2m)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding2m(Queue retryQueue2m, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue2m).to(retryExchange).with(retryRoutingKey2m);
    }

    @Bean
    public Queue retryQueue5m() {   // 5 mins
        return QueueBuilder.durable(retryQueue5m)
                .withArgument("x-message-ttl", ttl5m)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding5m(Queue retryQueue5m, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue5m).to(retryExchange).with(retryRoutingKey5m);
    }

    // dlq

    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange(dlqExchangeName);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(dlqQueueName).build();
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlqExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlqExchange).with(dlqRoutingKey);
    }


    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange campaignDispatchExchange(){
        return new DirectExchange(campaignDispatchExchangeName);
    }

    @Bean
    public Queue campaignDispatchQueue(){
        return new Queue(campaignDispatchQueueName, true);
    }

    @Bean
    public Binding campaignDispatchBinding(
            Queue campaignDispatchQueue,
            DirectExchange campaignDispatchExchange){

        return BindingBuilder.bind(campaignDispatchQueue)
                .to(campaignDispatchExchange)
                .with(campaignDispatchRoutingKey);
    }
}
