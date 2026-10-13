package com.manas.orderlifecycle.model;

import java.math.BigDecimal;

/**
 * Order-size categories for the Rule 605-style breakdown. FRACTIONAL and
 * ODD_LOT are their own categories rather than folded into the smallest
 * round-lot bucket -- the claim is explicit that both are included, and a
 * real Rule 605 report treats a fractional share and a 40-share odd lot as
 * execution-quality populations with different expected behavior from a
 * round lot, not noise to round away.
 */
public enum SizeCategory {
    FRACTIONAL,       // not a whole number of shares
    ODD_LOT,          // 1-99 whole shares
    ROUND_LOT_SMALL,  // 100-499
    ROUND_LOT_MEDIUM, // 500-1,999
    ROUND_LOT_LARGE,  // 2,000-4,999
    BLOCK;            // 5,000+

    public static SizeCategory classify(BigDecimal qty) {
        if (qty.stripTrailingZeros().scale() > 0) {
            return FRACTIONAL;
        }
        long whole = qty.longValueExact();
        if (whole < 100) return ODD_LOT;
        if (whole < 500) return ROUND_LOT_SMALL;
        if (whole < 2000) return ROUND_LOT_MEDIUM;
        if (whole < 5000) return ROUND_LOT_LARGE;
        return BLOCK;
    }
}
