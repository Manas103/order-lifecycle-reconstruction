# Order Lifecycle Reconstruction and Rule 605 Statistics

A Java 21 pipeline that generates a simulated exchange order-event log,
appends it to a SQL database as an **append-only** stream, reconstructs
every order's **lifecycle** by replaying that log, and computes
**Rule 605-style execution-quality statistics** by order-size category,
odd lots and fractional shares included. Every number below was measured
on this machine, not targeted, and the whole pipeline is run twice from
the same seed specifically to check that every figure it reports is
**exactly** reproducible, not just plausible.

---

## Why this exists

A real exchange or broker-dealer never has "the order" as a single row.
It has a stream of events -- New, Replace, Cancel, Partial Fill, Fill,
Reject -- arriving in whatever order the matching engine and the gateway
produced them, keyed loosely by an order id that can also be malformed,
duplicated, or reused. **Reconstruction** is the problem of turning that
raw stream back into "what actually happened to this order, from
submission to its final state", which is also the exact problem a real
Rule 605 execution-quality report, or a regulator's T+3 correction
workflow, is built on top of. This project is a small, honest version of
that: a generator that produces a self-consistent log plus a seeded
population of realistic defects, a reconstructor that recovers the
lifecycles and routes the defects to a correction queue by name, and a
SQL aggregation that turns the result into the kind of by-size-category
statistics Rule 605 actually reports.

---

## Honest framing, up front

- **Simulated order flow, not a real exchange feed.** `OrderEventGenerator`
  produces a self-consistent stream (every Replace/Cancel/Fill references
  an order that genuinely has an open NEW), not captured market data.
- **H2 in PostgreSQL-compatibility mode (`MODE=PostgreSQL`) stands in for
  real PostgreSQL**, exactly as this portfolio's other Java/SQL repos
  already do (securities-settlement-matching,
  strategy-parameter-rollout-gate): no standalone PostgreSQL instance was
  reachable in this build environment. `MODE=PostgreSQL` makes H2 reject
  the things real PostgreSQL would reject (e.g. implicit numeric
  truncation) rather than silently being more permissive than production.
- **Plain `javac`/`java`, no Maven or Gradle.** No `mvn` or `gradle`
  executable was available in this build environment (only a populated
  `~/.m2` cache from a previous session, no launcher). The project's only
  third-party dependency, the H2 driver, is fetched straight from Maven
  Central into a gitignored `lib/` (see Building and running) rather than
  vendored, the same way the C++ side of this portfolio fetches GoogleTest
  via CMake's `FetchContent` instead of committing it.
- **"Millisecond-or-finer" means the clock is in microseconds.** Every
  event timestamp is recorded in microseconds since a fixed epoch
  (`event_ts_us`), and time-to-execution is reported as a `double`
  millisecond value (e.g. `1649.6376`), i.e. with sub-millisecond
  resolution, not rounded to the nearest whole millisecond.
- **`java.util.Random`, not a hand-rolled PRNG.** The sibling C++ repos in
  this portfolio write their own splitmix64 generator because
  `std::uniform_int_distribution`'s algorithm is implementation-defined in
  the C++ standard. `java.util.Random`'s algorithm and its `nextInt`
  rejection-sampling formula are specified exactly in the JDK's own
  documentation, so a fixed seed reproduces the identical draw sequence on
  any conforming JVM without needing a custom generator.
- **Every number below was measured on this machine**: AMD Ryzen 7
  7800X3D (8 physical / 16 logical cores), Windows 11 build 10.0.26200,
  Temurin JDK 21.0.11, H2 2.3.232. The exact commands are given in
  Building and running.

---

## Architecture

```
sql/schema.sql                         order_events (append-only), correction_queue,
                                        lifecycle_summary -- the only DDL in the project

src/main/java/com/manas/orderlifecycle/
  model/EventType.java                 NEW, REPLACE, CANCEL, PARTIAL_FILL, FILL, REJECT
  model/Side.java                      BUY, SELL
  model/SizeCategory.java              FRACTIONAL / ODD_LOT / 4 round-lot bands, by qty
  model/OrderEvent.java                one append-only log row

  generate/OrderEventGenerator.java    deterministic, self-consistent order flow, plus
                                        5 kinds of seeded, always-caught malformed events
  generate/ReservoirEntry.java         a sampled valid order (id, NEW ts, qty), so the
                                        malformed generator can build realistic defects
                                        without holding all 3.1M orders' state in memory

  db/Db.java                           H2 (MODE=PostgreSQL) connection + schema loader
  db/EventLogWriter.java               the ONLY class that writes order_events; every
                                        statement it prepares is an INSERT

  reconstruct/LifecycleReconstructor.java  pure function: one order's sorted events in,
                                        a lifecycle result (or none) plus corrections out
  reconstruct/ReconstructionRunner.java    the streaming SQL driver around it: one
                                        forward-only, small-fetch-size scan ordered by
                                        (order_id, event_seq), grouped by consecutive rows
  reconstruct/{Reason,TerminalState,CorrectionEntry,LifecycleResult,GroupResult}.java

  rule605/Rule605Calculator.java       the one real SQL GROUP BY aggregation in the project
  rule605/CategoryStats.java

  Main.java                            generate -> log -> reconstruct -> aggregate -> print,
                                        twice, then diffs every figure between the two runs
  RunReport.java                       every figure one run produced, field-by-field diffable

src/test/java/com/manas/orderlifecycle/Tests.java   hand-rolled check suite (plain javac/java)
```

### Why reconstruction is a pure function with a thin SQL driver around it

`LifecycleReconstructor.reconstructGroup(List<OrderEvent>)` takes one
order's events, already sorted, and returns a result. It does not touch a
`Connection`, a `ResultSet`, or anything else that would make it hard to
unit test. `ReconstructionRunner` is the only class that knows about SQL,
and its entire job is grouping consecutive rows of one ordered scan and
handing each group to the pure function. That split is what makes the 12
hand-built scenarios in `Tests.java` -- a simple fill, a replace then
fill, three partial fills that must sum exactly, a malformed fill sorted
*between* two real ones -- fast, deterministic unit tests instead of
slow, order-dependent database tests.

### Why quantities are integer "qty units" during generation

`OrderEventGenerator` accumulates every partial fill as a `long` count of
0.0001-share units, converting to `BigDecimal` only at the moment an
event is emitted. Partial-fill arithmetic (take 10%-60% of what remains,
repeat up to 4 times, fill the rest) is exact integer division with no
rounding-mode decisions to get wrong and no floating point to make two
runs disagree in the last bit -- the same reason the sibling C++ decoder
in this portfolio keeps prices as integer ticks rather than floats.

### Why a lifecycle's size category is fixed at the original NEW, not the latest REPLACE

A Replace can change an order's declared quantity (`declared_qty` inside
`reconstructGroup` is updated and used to bound later fills), but
`size_category` is computed once, from the very first NEW's quantity, and
never recomputed. An order entered as a 40-share odd lot that gets
upsized to 400 shares by a later Replace is still, for execution-quality
reporting purposes, the order that was originally entered as an odd lot;
recategorizing it after the fact would make the category boundaries
depend on amendment history rather than on how the order was actually
placed.

---

## Validation

1. **Hand-built reconstruction scenarios** (`Tests.java`, 44 checks): a
   simple fill, a replace-then-fill (confirming the replaced quantity is
   what gets filled while the *original* quantity still drives the size
   category), three partial fills that must sum exactly to the declared
   quantity, a cancel after a partial fill (`PARTIALLY_FILLED_CANCELED`,
   distinct from a cancel with no fill at all), a reject, and one each of
   the five malformed-event kinds below, including a malformed fill
   spliced *between* two real fills for the same order to confirm it does
   not disturb either real one.
2. **The append-only invariant, checked, not just claimed.** No class in
   this project issues `UPDATE order_events` or `DELETE FROM
   order_events` anywhere (grep the source and find nothing). `Tests.java`
   additionally loads four events, runs a full reconstruction pass, and
   asserts `order_events`'s row count is identical before and after.
3. **280 is not involved here, 18,400 is.** `generateMalformed` emits
   exactly 3,680 events of each of five kinds; every one is constructed so
   it is caught by its check *regardless of insertion order* (see
   Findings for why), and the full-scale run below confirms all
   18,400 of 18,400 land in `correction_queue` with the right reason, none
   silently absorbed into a lifecycle.
4. **Reproducibility, checked by running the whole pipeline twice.**
   `Main` runs generate-log-reconstruct-aggregate once into
   `olr_run1`, again from the same seed into a fresh `olr_run2`, and diffs
   every reported figure -- event count, lifecycle count, correction count
   and reason breakdown, and all six size categories' order counts,
   executed counts, average time-to-exec, and average fill rate -- field
   by field. See Measured results; both runs produced bit-identical
   figures.

---

## Findings

**A column was 1 character too narrow, and only the longest terminal
state name tripped it.** `lifecycle_summary.terminal_state` was first
declared `VARCHAR(24)`. `FILLED`, `CANCELED`, `REJECTED`, and `OPEN` all
fit; `PARTIALLY_FILLED_CANCELED` is 25 characters, and H2 (correctly, in
`MODE=PostgreSQL`) raised a data exception on the very first order that
reached that state rather than silently truncating it. Fixed by widening
the column to `VARCHAR(32)`. The honest reason this one shipped at all is
that early manual testing used small sample sizes where a
partially-filled-then-canceled order happened not to come up before the
column width was reviewed -- exactly the kind of bug a fixed-width string
column should be chosen to make impossible, not discovered by chance.

**The second of two full-scale runs ran out of heap, and the honest cause
was a connection-lifetime setting, not "the data is too big".** The first
attempt at this project's core reproducibility check -- run the whole
12M+-event pipeline once, then again from the same seed -- used H2's
`DB_CLOSE_DELAY=-1` on the JDBC URL, copied from this portfolio's other
H2-backed Java repos. Run 1 alone used well under the 6 GiB heap given to
it and finished in under a minute; run 2 then failed with
`OutOfMemoryError` building its own copy of the same tables. The
discriminating fact was that measurement: run 1's own footprint was
known and comfortably small, so "the data is too big" could not explain
an OOM that only ever happened on the *second* run. `DB_CLOSE_DELAY=-1`
keeps an in-memory H2 database resident even after its one connection is
closed -- a deliberate choice in the sibling repos' test suites, which
open many short-lived connections against one logical database across a
test run and need it to outlive any single one of them. This project
opens exactly one connection per run and wants the opposite: the database
dropped the moment that connection closes, so a second run does not have
to hold both runs' 12M+-row tables in heap at once. Removing
`DB_CLOSE_DELAY=-1` fixed it; both full-scale runs (12,373,258 events
each) then completed one after the other inside an 8 GiB heap with room
to spare.

**Every seeded defect is constructed to be caught regardless of where in
the log it lands, which is what makes 18,400 of 18,400 a guarantee rather
than a probability.** `UNLINKABLE_ORDER_ID` events use order ids outside
the 1..3,100,000 range ever given a NEW, so they can never resolve to a
real order no matter when they are read. `DUPLICATE_NEW` and
`TIMESTAMP_BEFORE_NEW` events are generated after the entire valid
dataset and therefore always sort after a reused order's real NEW within
that order's group (reconstruction orders strictly by `event_seq`, the
global append order). `FILL_EXCEEDS_DECLARED_QTY` events use 10x a real
order's own declared quantity, a margin no legitimate Replace in this
generator ever closes. None of the five relies on the malformed event
landing in a particular position relative to its sibling valid events --
each is checked against state the reconstructor already knows by the time
it is reached, which is also what `testMalformedEventDoesNotCorruptSiblingValidEvents`
in `Tests.java` exercises directly.

---

## Measured results

Machine: AMD Ryzen 7 7800X3D (8 physical / 16 logical cores), Windows 11
build 10.0.26200, Temurin JDK 21.0.11 (OpenJDK), H2 2.3.232, `-Xmx8g`. Raw
output in `docs/pipeline_run_output.txt` and `docs/test_output.txt`.

| Claim | Target | Measured | Meets claim |
|---|---|---|---|
| Simulated order events | 12M+ | **12,373,258** | Yes |
| Lifecycles keyed on order id | 3.1M | **3,100,000** (exactly one per generated order, by construction) | Yes |
| Append-only log | implemented and correct | no `UPDATE`/`DELETE` against `order_events` anywhere in source; row count unchanged before/after reconstruction in `Tests.java` | Yes |
| Rule 605-style stats by order-size category, odd lots and fractional shares included | implemented and correct | 6 categories reported (FRACTIONAL, ODD_LOT, and 4 round-lot bands), each with order count, executed count, average time-to-exec, and average fill rate, computed by one SQL `GROUP BY` | Yes |
| Average time to execution, millisecond-or-finer | implemented and correct | e.g. ROUND_LOT_SMALL **1649.6376 ms** average (microsecond-resolution clock, reported as a `double` millisecond value) | Yes |
| Seeded malformed/unlinkable events queued for T+3 correction | 18,400 of 18,400 | **18,400 / 18,400** (3,680 of each of 5 reasons), every one named by reason in `correction_queue.reason` | Yes |
| Every figure reproduced exactly on re-run | exact | **PASS**: event count, lifecycle count, correction count and per-reason breakdown, and all 6 categories' 4 statistics, bit-identical between run 1 and run 2 | Yes |

Full by-category breakdown (identical on both runs):

| Size category | Orders | Executed | Avg time-to-exec (ms) | Avg fill rate |
|---|---:|---:|---:|---:|
| FRACTIONAL | 156,097 | 128,141 | 1646.8840 | 94.40% |
| ODD_LOT | 464,871 | 381,332 | 1652.7074 | 94.41% |
| ROUND_LOT_SMALL | 1,240,481 | 1,017,328 | 1649.6376 | 94.42% |
| ROUND_LOT_MEDIUM | 773,756 | 634,675 | 1651.6275 | 94.42% |
| ROUND_LOT_LARGE | 309,647 | 254,096 | 1646.9377 | 94.44% |
| BLOCK | 155,148 | 127,270 | 1653.0589 | 94.40% |

The average time-to-exec is nearly flat across categories (all within
about 0.4% of each other) because the generator's per-event clock advance
does not itself depend on order size; this is a property of the
generator's timing model, not a claim about how real execution speed
varies with order size.

Each full run (generate 12.37M events, reconstruct, aggregate) took
56-62 seconds; the two runs together, plus the 44-check test suite, run
in under three minutes total.

---

## Building and running

Requires JDK 21 and `curl`. No Maven or Gradle is used.

```bash
# fetch the one third-party dependency (not vendored, see Honest framing)
mkdir -p lib
curl -sL -o lib/h2-2.3.232.jar https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar

# compile
javac -d build/classes -cp lib/h2-2.3.232.jar $(find src/main/java -name '*.java')
javac -d build/classes -cp lib/h2-2.3.232.jar $(find src/main/java src/test/java -name '*.java')

# tests (44 checks, hand-built scenarios, a fraction of a second)
java -cp build/classes:lib/h2-2.3.232.jar com.manas.orderlifecycle.Tests sql/schema.sql

# full measured run: 3,100,000 orders, 3,680 seeded defects per reason,
# twice, with the reproducibility diff at the end (56-62s per run)
java -Xmx8g -cp build/classes:lib/h2-2.3.232.jar com.manas.orderlifecycle.Main 3100000 3680 sql/schema.sql 20000
```

On Windows PowerShell, replace `:` with `;` in the classpath and
`$(find ... -name '*.java')` with
`(Get-ChildItem -Recurse -Filter *.java src\main\java | % FullName)`
(see the exact commands used to produce `docs/pipeline_run_output.txt` in
this repo's own build history).

`Main` takes `<numOrders> <countPerReason> <schemaSqlPath> [batchSize]`;
a quick smoke run is `java ... com.manas.orderlifecycle.Main 50000 368
sql/schema.sql 5000` (a few hundred milliseconds).

## Limitations

- Simulated order flow, not a real exchange feed or a real broker-dealer's
  order management system.
- H2 in `MODE=PostgreSQL`, not real PostgreSQL (see Honest framing); no
  standalone PostgreSQL instance was reachable in this build environment.
- The five malformed-event kinds are each a single, deliberately
  unambiguous defect; no case combines two simultaneous defects on one
  event, and no case tests a defect that depends on cross-order state
  (e.g. two different orders racing for the same, erroneously shared,
  order id).
- Rule 605 in reality defines specific, narrower order-size buckets (and
  distinguishes market/marketable-limit/other-limit order types, which
  this project does not model); the six categories here are this
  project's own, order-size-only approximation of that style of report,
  not a claim of regulatory conformance.
- `time_to_exec_ms` measures from an order's NEW to its *first* fill, not
  its last; a multi-fill order's full execution can finish meaningfully
  later than this figure, and the report does not separately state that.
- The generator's per-event clock advance is independent of order size,
  which is why the measured average time-to-exec is nearly flat across
  categories (see Measured results); this models the mechanics of
  reconstruction correctly but is not a claim about real execution-speed
  behavior by order size.
