package com.loopers.domain.order;

import java.util.List;

public record OrderCreatedEvent(Long orderId, Long userId, List<OrderCreatedItem> items) {

    public record OrderCreatedItem(Long productId, Long quantity) {
    }

    public static OrderCreatedEvent from(OrderModel order) {
        List<OrderCreatedItem> items = order.getItems().stream()
                .map(item -> new OrderCreatedItem(item.getProductId(), item.getQuantity()))
                .toList();
        return new OrderCreatedEvent(order.getId(), order.getUserId(), items);
    }

    public List<Long> productIds() {
        return items.stream().map(OrderCreatedItem::productId).toList();
    }
}
