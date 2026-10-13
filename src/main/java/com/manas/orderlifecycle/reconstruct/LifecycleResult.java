package com.manas.orderlifecycle.reconstruct;

import com.manas.orderlifecycle.model.Side;
import com.manas.orderlifecycle.model.SizeCategory;

import java.math.BigDecimal;

public final class LifecycleResult {
    public final long orderId;
    public final String symbol;
    public final Side side;
    public final BigDecimal originalQty;
    public final SizeCategory sizeCategory;
    public final long newTsUs;
    public final TerminalState terminalState;
    public final BigDecimal totalFilledQty;
    public final Long firstFillTsUs;   // null if never filled
    public final Double timeToExecMs;  // null if never filled

    public LifecycleResult(long orderId, String symbol, Side side, BigDecimal originalQty,
                            SizeCategory sizeCategory, long newTsUs, TerminalState terminalState,
                            BigDecimal totalFilledQty, Long firstFillTsUs, Double timeToExecMs) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.originalQty = originalQty;
        this.sizeCategory = sizeCategory;
        this.newTsUs = newTsUs;
        this.terminalState = terminalState;
        this.totalFilledQty = totalFilledQty;
        this.firstFillTsUs = firstFillTsUs;
        this.timeToExecMs = timeToExecMs;
    }
}
