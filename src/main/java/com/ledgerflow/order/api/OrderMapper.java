package com.ledgerflow.order.api;

import java.util.List;

import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;

/**
 * Hand-written translation between the REST models and the domain.
 *
 * <p>Stateless by design, so it is a static utility rather than a bean: the
 * mapping is a pure function of its input and has nothing to inject.
 */
final class OrderMapper {

    private OrderMapper() {
    }

    static List<OrderItem> toDomainItems(CreateOrderRequest request) {
        return request.items().stream()
                .map(item -> new OrderItem(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
    }

    static OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getStatus(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
