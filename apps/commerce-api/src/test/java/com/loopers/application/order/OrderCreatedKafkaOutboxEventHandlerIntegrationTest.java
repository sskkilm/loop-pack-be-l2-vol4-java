package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.domain.outbox.OutboxModel;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// 실제 Kafka 브로커(Testcontainers)에 발행된 메시지를 컨슈머로 폴링해 검증한다.
@SpringBootTest
class OrderCreatedKafkaOutboxEventHandlerIntegrationTest {

    private static final String TOPIC = "order-events";

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Consumer<String, String> createConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                String.join(",", kafkaProperties.getBootstrapServers()), "test-" + UUID.randomUUID(), "true");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    private void savePendingOutbox(String eventId, Long orderId) {
        String payload = writePayload(eventId, orderId);
        outboxRepository.save(new OutboxModel(eventId, "Order", String.valueOf(orderId), "ORDER_CREATED", payload));
    }

    private String writePayload(String eventId, Long orderId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId,
                    "orderId", orderId,
                    "userId", 1L,
                    "items", List.of(Map.of("productId", 10L, "quantity", 2L))
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("ORDER_CREATED 아웃박스를 릴레이가 처리할 때,")
    @Nested
    class HandlePendingOutbox {

        @DisplayName("order-events 토픽에 orderId를 키로 봉투가 발행되고 아웃박스가 DONE 처리된다.")
        @Test
        void publishesEnvelopeAndMarksDone_whenOrderCreatedOutboxIsPending() throws Exception {
            // given
            Long orderId = 123L;
            String eventId = UUID.randomUUID().toString();
            savePendingOutbox(eventId, orderId);

            try (Consumer<String, String> consumer = createConsumer()) {
                // when
                outboxRelay.relay();

                // then
                ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
                JsonNode envelope = objectMapper.readTree(record.value());
                assertAll(
                        () -> assertThat(record.key()).isEqualTo(String.valueOf(orderId)),
                        () -> assertThat(envelope.get("eventId").asText()).isEqualTo(eventId),
                        () -> assertThat(envelope.get("aggregateType").asText()).isEqualTo("Order"),
                        () -> assertThat(envelope.get("aggregateId").asText()).isEqualTo(String.valueOf(orderId)),
                        () -> assertThat(envelope.get("eventType").asText()).isEqualTo("ORDER_CREATED"),
                        () -> assertThat(envelope.get("payload").get("items").get(0).get("productId").asLong()).isEqualTo(10L),
                        () -> assertThat(outboxRepository.findAllByStatusOrderByIdAsc(OutboxStatus.PENDING)).isEmpty()
                );
            }
        }
    }
}
