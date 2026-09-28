package com.ledgerflow.order.api;

import java.util.List;

import com.ledgerflow.order.model.dto.CreateOrderItemRequest;
import com.ledgerflow.order.model.dto.CreateOrderRequest;
import com.ledgerflow.order.model.dto.OrderItemResponse;
import com.ledgerflow.order.model.dto.OrderResponse;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;

/**
 * Hand-written translation between the REST models ({@code model.dto}) and
 * the persistence entities ({@code model.entity}).
 *
 * <p>Stateless by design, so it is a static utility rather than a bean: the
 * mapping is a pure function of its input and has nothing to inject.
 */
final class OrderMapper {

    private OrderMapper() {
    }

    /**
     * Builds a brand-new {@link OrderEntity} from a create request. Item
     * ids and the order foreign key are assigned by
     * {@link OrderEntity#createNew}, not here — this only needs to carry
     * each item's own fields.
     */
    static OrderEntity toNewEntity(CreateOrderRequest request) {
        List<OrderItemEntity> items = request.items().stream()
                .map(OrderMapper::toDraftItem)
                .toList();
        return OrderEntity.createNew(request.customerId(), request.currency(), request.totalAmount(), items);
    }

    private static OrderItemEntity toDraftItem(CreateOrderItemRequest item) {
        return OrderItemEntity.builder()
                .productId(item.productId())
                .quantity(item.quantity())
                .unitPrice(item.unitPrice())
                .build();
    }

    static OrderResponse toResponse(OrderEntity order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getStatus(),
                items,
                order.getCreatedAt().toInstant(),
                order.getUpdatedAt().toInstant());
    }
}
