package com.loopers.interfaces.event.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.ProductViewedEvent;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// send()는 @Async라 주입된 프록시로 호출하면 별도 스레드로 넘어간다.
// 프록시를 벗긴 대상 객체로 호출해 본문(발행 배선)을 동기·결정적으로 검증한다(LikeFastPathIntegrationTest 패턴 재사용).
@SpringBootTest
class ProductViewedKafkaEventListenerIntegrationTest {

    private static final String TOPIC = "catalog-events";

    @Autowired
    private ProductViewedKafkaEventListener listener;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ProductViewedKafkaEventListener target;

    @BeforeEach
    void setUp() {
        target = AopTestUtils.getUltimateTargetObject(listener);
    }

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

    @DisplayName("상품 조회 이벤트를 처리할 때,")
    @Nested
    class Send {

        @DisplayName("catalog-events 토픽에 productId를 키로 PRODUCT_VIEWED 봉투가 발행된다.")
        @Test
        void publishesEnvelope_whenProductViewedEventSent() throws Exception {
            // given
            Long productId = 42L;
            ProductViewedEvent event = ProductViewedEvent.of(productId);

            try (Consumer<String, String> consumer = createConsumer()) {
                // when
                target.send(event);

                // then
                ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
                JsonNode envelope = objectMapper.readTree(record.value());
                assertAll(
                        () -> assertThat(record.key()).isEqualTo(String.valueOf(productId)),
                        () -> assertThat(envelope.get("eventId").asText()).isEqualTo(event.eventId()),
                        () -> assertThat(envelope.get("aggregateType").asText()).isEqualTo("Product"),
                        () -> assertThat(envelope.get("aggregateId").asText()).isEqualTo(String.valueOf(productId)),
                        () -> assertThat(envelope.get("eventType").asText()).isEqualTo("PRODUCT_VIEWED")
                );
            }
        }
    }
}
