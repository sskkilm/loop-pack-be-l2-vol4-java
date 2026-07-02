package com.loopers.application.like;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.outbox.OutboxModel;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// 실제 Kafka 브로커(Testcontainers)에 발행된 메시지를 컨슈머로 폴링해 검증한다.
@SpringBootTest
class ProductLikeKafkaOutboxEventHandlerIntegrationTest {

    private static final String TOPIC = "catalog-events";

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

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

    private Long saveProduct() {
        BrandModel brand = brandRepository.save(new BrandModel("브랜드"));
        return productRepository.save(new ProductModel(brand.getId(), "상품", BigDecimal.valueOf(1000))).getId();
    }

    private void savePendingOutbox(String eventId, Long productId, String eventType) {
        String payload = writePayload(eventId, productId);
        outboxRepository.save(new OutboxModel(eventId, "Product", String.valueOf(productId), eventType, payload));
    }

    private String writePayload(String eventId, Long productId) {
        try {
            return objectMapper.writeValueAsString(Map.of("eventId", eventId, "productId", productId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("PRODUCT_LIKED/PRODUCT_UNLIKED 아웃박스를 릴레이가 처리할 때,")
    @Nested
    class HandlePendingOutbox {

        @DisplayName("catalog-events 토픽에 aggregateId를 키로 봉투가 발행되고 아웃박스가 DONE 처리된다.")
        @Test
        void publishesEnvelopeAndMarksDone_whenProductLikedOutboxIsPending() throws Exception {
            // given
            Long productId = saveProduct();
            String eventId = UUID.randomUUID().toString();
            savePendingOutbox(eventId, productId, "PRODUCT_LIKED");

            try (Consumer<String, String> consumer = createConsumer()) {
                // when
                outboxRelay.relay();

                // then
                ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
                JsonNode envelope = objectMapper.readTree(record.value());
                assertAll(
                        () -> assertThat(record.key()).isEqualTo(String.valueOf(productId)),
                        () -> assertThat(envelope.get("eventId").asText()).isEqualTo(eventId),
                        () -> assertThat(envelope.get("aggregateType").asText()).isEqualTo("Product"),
                        () -> assertThat(envelope.get("aggregateId").asText()).isEqualTo(String.valueOf(productId)),
                        () -> assertThat(envelope.get("eventType").asText()).isEqualTo("PRODUCT_LIKED"),
                        () -> assertThat(envelope.get("payload").get("productId").asLong()).isEqualTo(productId),
                        () -> assertThat(outboxRepository.findAllByStatusOrderByIdAsc(OutboxStatus.PENDING)).isEmpty()
                );
            }
        }

        @DisplayName("catalog-events 토픽에 PRODUCT_UNLIKED 봉투가 발행된다.")
        @Test
        void publishesEnvelope_whenProductUnlikedOutboxIsPending() throws Exception {
            // given
            Long productId = saveProduct();
            String eventId = UUID.randomUUID().toString();
            savePendingOutbox(eventId, productId, "PRODUCT_UNLIKED");

            try (Consumer<String, String> consumer = createConsumer()) {
                // when
                outboxRelay.relay();

                // then
                ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
                JsonNode envelope = objectMapper.readTree(record.value());
                assertThat(envelope.get("eventType").asText()).isEqualTo("PRODUCT_UNLIKED");
            }
        }
    }
}
