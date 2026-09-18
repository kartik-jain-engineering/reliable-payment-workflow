package com.ledgerflow.order.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/**
 * R2DBC-backed implementation of the domain {@link OrderRepository} port.
 *
 * <p>Spring Data R2DBC has no aggregate/one-to-many support, so persisting an
 * order is an explicit multi-statement write: the {@code orders} row, then a
 * wholesale delete-and-reinsert of its {@code order_items}. Those statements
 * must land together, hence the {@link TransactionalOperator} wrapped around
 * {@link #save} — the boundary sits here because this is the only place where
 * more than one statement is issued.
 *
 * <p>The operator is built by hand from the auto-configured
 * {@link ReactiveTransactionManager}; Boot does not contribute a
 * {@code TransactionalOperator} bean of its own.
 */
@Repository
class OrderRepositoryAdapter implements OrderRepository {

    private final R2dbcEntityTemplate template;
    private final TransactionalOperator transactionalOperator;

    OrderRepositoryAdapter(R2dbcEntityTemplate template, ReactiveTransactionManager transactionManager) {
        this.template = template;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    /**
     * Ids are assigned by the domain, not the database, so the order row is
     * already "identified" before it has ever been stored. A bare
     * {@code template.save(row)} would therefore issue an UPDATE that matches
     * nothing and silently drop a brand-new order. The existence check below
     * makes the INSERT-vs-UPDATE choice explicit instead of inferring it from
     * the id being non-null.
     */
    @Override
    public Mono<Order> save(Order order) {
        Mono<OrderRow> persistedRow = template.selectOne(query(where("id").is(order.getId())), OrderRow.class)
                .flatMap(existing -> template.update(existing.applyChanges(order)))
                .switchIfEmpty(Mono.defer(() -> template.insert(OrderRow.forInsert(order))));

        return persistedRow
                .then(replaceItems(order))
                .thenReturn(order)
                .as(transactionalOperator::transactional);
    }

    @Override
    public Mono<Order> findById(UUID id) {
        Mono<OrderRow> rowMono = template.selectOne(query(where("id").is(id)), OrderRow.class);
        Mono<List<OrderItem>> itemsMono = template.select(OrderItemRow.class)
                .matching(query(where("orderId").is(id)))
                .all()
                .map(OrderItemRow::toDomain)
                .collectList();

        return Mono.zip(rowMono, itemsMono).map(rowAndItems -> rowAndItems.getT1().toDomain(rowAndItems.getT2()));
    }

    private Mono<Void> replaceItems(Order order) {
        return template.delete(query(where("orderId").is(order.getId())), OrderItemRow.class)
                .thenMany(Flux.fromIterable(order.getItems())
                        .concatMap(item -> template.insert(OrderItemRow.forInsert(order.getId(), item))))
                .then();
    }
}
