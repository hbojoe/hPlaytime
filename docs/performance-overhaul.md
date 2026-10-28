# Performance investigation and implementation

## Verified report

The original implementation at commit `9abcceb` performed storage I/O directly from synchronous Bukkit listeners,
commands and `runTaskTimer`. No executor, asynchronous storage, connection pool or read cache
existed. This is a credible cause of tick stalls; the severity on the reporting server has not
been measured because no profile, database latency, population or server configuration was supplied.

The original path was real but conditional:

| Original source | Verified behavior |
| --- | --- |
| `PlaytimeManager.handleActivity`, lines 62–75 | Ordinary interactions only changed the activity timestamp. A missing session initialized storage; returning after the AFK timeout called `flushPlayer`. It did **not** access MySQL on every interaction. |
| `PlaytimeManager.flushPlayer`, lines 220–249 | Updated visibility, then saved eligible playtime and events inline. Empty increments skipped the playtime write, but visibility was still written. |
| `MysqlPlaytimeStorage.connection`, lines 309–321 | Used `DriverManager.getConnection` with no pool or explicitly configured connect/socket timeout. |
| `MysqlPlaytimeStorage.getSnapshot`, lines 126–148 | Ran four SELECTs using **one** connection. The claim that every individual query opened a connection was inaccurate; each storage operation opened one. |
| `HPlaytime.startFlushTask`, lines 67–81 | Flushed all players serially in a synchronous timer, every 60 seconds by default. |
| `PlaytimeManager.getTop`, lines 166–175 | Every leaderboard command flushed every online player before reading rankings. |
| `HPlaytimePlaceholderExpansion.onRequest/topEntries`, lines 39–56 and 134–140 | Performed uncached storage reads in the caller's thread; a synchronous display integration therefore blocked the main thread. Placeholder execution is not intrinsically guaranteed to be synchronous. |
| `LocalPlaytimeStorage`, lines 27–70 and 87–151 | Reparsed/rewrote player YAML for updates; leaderboards and name queries scanned player files. |
| `EventManager.addPlaytime/saveEvent`, lines 109–130 and 211–229 | Rewrote an entire event file for each qualifying player, even when main storage used MySQL. |
| `PlaytimeCommand`, lines 45–62 and 494–510 | Reload read configuration/language synchronously. Name completion queried storage and enumerated vanilla offline players. |

These are pre-change line numbers. In the old implementation, a typical same-day flush of 100
eligible online players opened 200 MySQL connections and issued 500 SQL writes plus 100 commits,
or performed 200 player-YAML load/save cycles. Events added up to players × overlapping active
events full event-file rewrites. These are source-derived counts, not measured timings.

## Release-blocker fixes in 1.0.7

The final audit found two failures in the initial overhaul: cached absolute MySQL totals overwrote
other servers' increments, and the differential write cache could skip a reset after an uncertain
commit. The implementation below replaces that protocol.

### Transactional operations and shared resets

Main-thread accounting produces immutable, timestamped eligible intervals and explicit reset
operations. Each sealed operation gets one UUID and a deterministic payload fingerprint. An
uncertain in-flight operation keeps its UUID and payload; newer changes/resets wait behind it.
Shutdown captures both in-flight and queued operations in order.

MySQL uses atomic `millis = millis + VALUES(millis)` writes to the original playtime table. Three
auxiliary InnoDB tables are created alongside existing tables:

- `<prefix>persistence_operations`: operation UUID and payload fingerprint.
- `<prefix>persistence_state`: global reset cutoff and a transaction serialization row.
- `<prefix>persistence_players`: per-player reset and metadata clocks.

For long legacy prefixes, suffixes shorten to `pt_state`, `pt_clock`, or `pt_ops` where necessary
to stay within MySQL's 64-character identifier limit. The original maximum 56-character prefix
remains usable.

An operation's receipt commits in the same transaction as all its changes. If commit or connection
close throws, a fresh connection checks the durable receipt. An unavailable verification keeps the
operation/checkpoint pending; it never advances an assumed cached baseline. Replaying a receipted
operation does nothing. Reusing its ID with another payload is rejected.
If rollback itself fails, the connection is evicted/aborted; enabling auto-commit on an unresolved
transaction is forbidden because it could commit partial writes without a receipt.

All 1.0.7 mutations for a table prefix lock the serialization row, so player/global resets and
contributions cannot interleave partially. Resets delete the requested totals and record a database
epoch timestamp. Late intervals are clipped at that cutoff, retaining only their post-reset portion.
The SQL clock is read numerically to avoid JDBC/session timezone conversion. Last-seen values use
a monotonic maximum, and older name/visibility updates cannot overwrite newer metadata.

Original player/playtime columns and period keys are retained. Initialization verifies InnoDB,
because nontransactional engines cannot provide exactly-once receipts. No legacy totals are
rewritten just to enable the new protocol.

### Caches and the main thread

Bukkit player/world/permission access, session/event accounting, commands, and cache publication
remain on the server thread. SQL, configuration/language reads, player/event files, recovery files,
and close operations remain off-thread. Placeholder requests perform no storage access or waits.

MySQL state is refreshed asynchronously after saves and at `storage.mysql.cache-refresh-seconds`
(default 5, clamped to 1–60). A consistent repeatable-read snapshot includes totals, metadata, and
reset cutoffs. Comparison happens on the worker; the main thread updates affected cache entries
and overlays its unsaved intervals. Remote refresh cannot discard local pending time or count an
acknowledged interval twice. Rankings, names, and visibility refresh with that shared state.

Cross-server display consistency is **eventual**, measured from a remote transaction's commit.
Time not yet flushed by another server is not visible elsewhere. Use matching date/timezone
settings and synchronized server/database clocks. All writers must run 1.0.7 for coordinated
resets and receipt guarantees: 1.0.6 atomic additions remain format-compatible, but its reset
commands do not update the new shared cutoffs. Events remain per-server YAML competitions.

### Local persistence and recovery

Local player files retain the 1.0.6 YAML structure, including custom dotted date formats and extra
fields. Local metadata lives under `data/.persistence/`, outside the legacy player-file scan.
A forced absolute redo plan is written before any player files change; receipt publication comes
after player/state replacement. A partial local write replays the same absolute plan before any
later operation, preventing duplicate increments.

Every save stages an immutable recovery checkpoint before changing SQL/player/event data. A
separate single-thread recovery spool has four queue slots; the data worker has eight. Normal
operation has only one save in flight. Neither executor runs rejected work on the caller.
Retries checkpoint the older uncertain operations **and** newer queued changes. Recovery-only
snapshots do not seal or grow the live outbox. Verifying only the older SQL batch cannot acknowledge
or delete a checkpoint containing newer operations that have not yet been submitted to SQL.

The final shutdown checkpoint is admitted directly to the recovery spool, ahead of waiting for
the data worker. It includes uncertain and newer queued operations. A generation check prevents
an older save acknowledgement from deleting that newer checkpoint. Event-file generations prevent
a recovery file belonging to an older backend from resurrecting outdated event state or deletions.
The durable recovery sequence also advances across inactive backends and wall-clock corrections.
Logs have unique immutable filenames and do not grow the event-generation index.

A checkpoint is removed only after operations are verified, file/version writes succeed, and
readback succeeds. Failure retains it for replay. Format-2 checkpoints store operation IDs and
intervals, not cached absolute SQL totals. An absolute-total checkpoint from an **unreleased early
1.0.7 build** is rejected for manual review rather than guessed/replayed into shared tables.
Released 1.0.6 created no such recovery files.

Read and write limits both count the entire YAML document in Unicode code points, including
trailing whitespace. The shared limit is `64 * 1024 * 1024`. Oversized replacements are rejected
before touching the existing file. The outbox retains the rejected work, and errors identify the
failure; disk/size failures cannot be presented as a durable shutdown.

### Shutdown and lifecycle

Disable stops main-thread sessions, collects final operations/events, stages recovery independently,
then queues the final data save and close. Normal ticks never wait for persistence. Only disable
waits for a bounded drain (default 30 seconds, clamped to 1–60). A timeout/interruption does not
cancel accepted work. A registered JVM shutdown hook waits for the final recovery checkpoint
before normal process exit, even with a blocked database worker. The hook is removed after close.

The local folder lock stays owned while the worker drains. Reload first verifies the old backend's
barrier, prepares the new backend without closing the active one, then retains post-barrier
operations against the selected backend's own totals. A backend switch selects another dataset;
it does not migrate old totals. Keep all backend-specific checkpoints when changing settings.

Reset/event command success waits for verified persistence. A failed verification reports that the
accepted change remains pending. At most 64 command acknowledgements wait for the next batch;
a retry of old SQL operations cannot acknowledge a newer reset. Event log backlog remains capped
at 1,024. Event expiry runs after all players have been accounted and immediately requests a save.

### Validation

Run:

```text
gradlew clean test build buildReleaseJars --no-configuration-cache
```

Regression tests cover:

- Independent and concurrent writers; 100 ms + 200 ms produces exactly 300 ms.
- Lost commit acknowledgement followed by reset, late retry, and verified checkpoint removal.
- Failed rollback with partial writes, without an implicit commit or duplicate contribution.
- Unavailable readback, receipt payload mismatch, metadata ordering, and shared reset clipping.
- Shared-cache total/name/privacy invalidation without losing pending local intervals.
- FIFO/rejection, stable in-flight IDs, disconnect/reconnect, reset/reload ordering, AFK worlds,
  daily/monthly/DST boundaries, immutable concurrent placeholder reads, and legacy YAML.
- Partial local redo, blocked database with an independently durable final checkpoint, interrupted
  disable, and a child JVM actually executing its shutdown hook and replaying 300 ms after exit.
- Matching Unicode read/write limits, preservation of the previous checkpoint after oversize failure,
  event coalescing/expiry/deletion, dependency relocation, and Java 17 base bytecode.

H2's MySQL mode tests production operation SQL. For actual MySQL staging, set
`HPLAYTIME_TEST_MYSQL_HOST`, `HPLAYTIME_TEST_MYSQL_DATABASE`, and optionally
`HPLAYTIME_TEST_MYSQL_PORT`, `HPLAYTIME_TEST_MYSQL_USER`, `HPLAYTIME_TEST_MYSQL_PASSWORD`,
and `HPLAYTIME_TEST_MYSQL_SSL`. The opt-in test uses and removes an isolated generated table prefix.
It exercises production DDL, legacy rows, two instances, receipts, and reset cutoffs. Without these
variables it is explicitly skipped.

### Remaining staging checks

- Run the opt-in tests on supported MySQL versions and test network failure/reconnect with two real
  Paper servers, including resets, player transfers, reload, and server stop.
- Exercise PlaceholderAPI registration/requests and commands on the advertised Paper/Purpur versions
  and their required Java versions. Bytecode checks are not a runtime server-version matrix.
- Profile realistic histories. The cache retains stored histories, polling reads the shared dataset,
  and projection/index publication still consumes main-thread CPU. Operation receipts and reset
  metadata intentionally persist; do not prune them while old recovery can reference them.
- Back up all prefixed tables and the plugin directory consistently, including auxiliary tables,
  local persistence metadata, and recovery files. Restoring totals independently of receipts is unsafe.
- Hard kills before the spool finishes, disk exhaustion/hangs, and power loss cannot promise absolute
  durability. Normal JVM shutdown is tested; filesystem/hardware failure remains an operational limit.
