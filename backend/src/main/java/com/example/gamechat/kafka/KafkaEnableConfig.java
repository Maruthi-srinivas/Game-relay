package com.example.gamechat.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaEnableConfig {

    @Bean
    public KafkaAdmin.NewTopics chatTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("chat.message.persisted").partitions(1).replicas(1).build(),
                TopicBuilder.name("chat.user.joined").partitions(1).replicas(1).build(),
                TopicBuilder.name("chat.user.left").partitions(1).replicas(1).build(),
                TopicBuilder.name("chat.moderation").partitions(1).replicas(1).build()
        );
    }
}
