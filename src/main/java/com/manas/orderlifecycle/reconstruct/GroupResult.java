package com.manas.orderlifecycle.reconstruct;

import java.util.List;

/** The result of reconstructing one order_id's group of events: at most one
 * lifecycle row (present whenever a genuine NEW was ever found, even if some
 * of that order's other events were malformed and filtered out), plus zero
 * or more correction-queue entries for the events that were filtered. */
public final class GroupResult {
    public final LifecycleResult lifecycle; // null if no valid NEW was ever found
    public final List<CorrectionEntry> corrections;

    public GroupResult(LifecycleResult lifecycle, List<CorrectionEntry> corrections) {
        this.lifecycle = lifecycle;
        this.corrections = corrections;
    }
}
