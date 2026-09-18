/**
 * Order module — infrastructure layer.
 *
 * <p>Adapters for technical concerns: the R2DBC row types and the adapter
 * implementing the domain's persistence port. The row types are
 * package-private on purpose — the aggregate, not the row, is what leaves
 * this package.
 */
package com.ledgerflow.order.infrastructure;
