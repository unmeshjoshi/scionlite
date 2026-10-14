# Scion Lite

Scion Lite is a small teaching version of Scion, Anthropic's in-house source control system, built on
[tickloom](https://github.com/unmeshjoshi/tickloom). It follows the concepts in the engineering post
[Redesigning source control at Anthropic](https://www.anthropic.com/engineering/redesigning-source-control).
There is no public Scion code, so Scion Lite models only what the post describes. Anywhere the post is silent, the
choice is marked **[Lite assumption]** in the code and in [`mapping.md`](mapping.md). That file maps each class to
the part of the post it stands for.

Every tier runs as a tickloom process in one single-threaded tick loop. The tests run all of them on tickloom's
**simulated network**, and each node keeps its data on its own **simulated disk**. Crashes, partitions and packet
loss are injected deterministically.

## Build and test

```
./gradlew test
```

Requires Java 21. Dependencies come from Maven Central (`com.tickloom:tickloom` and `tickloom-testkit`
0.1.0-beta.4).

## The tiers

```
scn ──► scion-daemon ──► scion-cache ──► scion-server ──► Spanner
(client)  (one per node)   (one per region)   (several, stateless)  └──► cloud storage
```

| Tier | Class | What it does |
|---|---|---|
| scn | `scn.Scn` | The client. It talks only to its node's daemon, and it fetches, checks out, commits and lands. |
| scion-daemon | `daemon.ScionDaemon` | One per node (called a workstation here; the post describes daemons "In the fleet"). It stores objects for the node in a bounded (LRU) cache of immutable replies, and talks only to its region's cache. The post gives the bounded cache to the client **[Lite assumption]**. |
| scion-cache | `cache.ScionCache` | One per region. It caches immutable replies in memory, and on a miss it asks a server. |
| scion-server | `server.ScionServer` | Authoritative. It reads Spanner and cloud storage, computes derived data and creates changesets. Like every tier, it keeps no long-lived state. |
| Spanner | `spanner.Spanner` | **One node** standing in for Spanner. It answers Google Cloud Spanner's `CreateSession`, `Read`, `BeginTransaction`, `Commit` and `Rollback`, over three tables: `refs`, `changesets` and `root_tree`. |
| Cloud storage | `storage.CloudStorage` | One node. It holds immutable, compressed objects keyed by id. |

As in the post, the daemon, cache and server **answer identical RPCs** (`api.ScionApi`), so the client never needs
to know the topology. The tiers are strict: a daemon talks only to its cache, a cache only to servers, and only
servers talk to Spanner and cloud storage.

### Spanner is one node with Spanner's API

The post doesn't mention Raft, Paxos or consensus, and Scion Lite has none of its own. Scion Lite's `Spanner` is a
single node:
- Every reply waits until what it reports is durable in a journal on that node's simulated disk. A commit is one
  journal record, so a crash keeps all of its mutations or none.
- Google's Spanner paper (not the post) describes replicating each range of rows with Paxos across zones. Swapping in
  a replicated implementation would not change any caller.

Its calls follow Google's RPC reference for the `google.spanner.v1.Spanner` service
(https://cloud.google.com/spanner/docs/reference/rpc/google.spanner.v1). Message and field names are the
reference's, in camel case (`keySet`, `transactionId`, `insertOrUpdate`):

| Call | What Scion Lite supports |
|---|---|
| `CreateSession` | Every other call names a session. "Sessions can only execute one transaction at a time", so each server keeps a pool and takes a session for each call or transaction. Sessions live in memory, so a Spanner restart deletes them, and "If a session is deleted, requests to it return NOT_FOUND"; the server then creates a new session and tries once more. |
| `Read` | A table, columns and a `KeySet` of keys, `KeyRange`s or `all`. With no transaction it is a strong single-use read. A `TransactionSelector` can `begin` a read-write transaction or use one by `id`. Returns a `ResultSet` with `metadata.rowType`. |
| `BeginTransaction` | Read-write transactions with the `OPTIMISTIC` read lock mode only. |
| `Commit` | `Mutation`s `insert`, `update`, `insertOrUpdate`, `replace` and `delete`, applied atomically. Either a started transaction's id or a `singleUseTransaction`. Returns a `commitTimestamp`. |
| `Rollback` | As in the reference, it "returns OK if it successfully aborts the transaction, the transaction was already aborted, or the transaction isn't found". |

Errors carry the reference's codes: `ALREADY_EXISTS` for an insert of an existing row, `NOT_FOUND` for an update of a
missing one, and `ABORTED` when a row a transaction read has changed since. Values travel as in the reference's JSON
encoding: `STRING` as a string, `BYTES` as base64.

Spanner has no compare-and-swap call. A scion-server moves a ref with a read-write transaction: `Read` the ref's
row (beginning the transaction), compare it with the expected value, then `Commit` the new value or `Rollback`. On
`ABORTED` it retries the transaction from the beginning, as the reference says to. The Rollback is best effort: the
answer is already known, and Spanner drops an unfinished transaction when its session starts another or it sits
idle.

Not modelled: `DeleteSession`, other session calls and idle session deletion, `ExecuteSql` and DML, `StreamingRead`,
partitioned reads and queries, read-only transactions with stale reads, the `PESSIMISTIC` lock mode, indexes,
multi-column keys, `BatchWrite` and commit stats. Commit timestamps increase but are not TrueTime.

Cloud storage is a single node for the same reason.

## Data model

- **Changeset** (`changeset.Changeset`):
  - It holds its parents, author, time and message, plus **the files changed relative to its first parent**: each
    one is a path and a blob id, or a deleted file.
  - There is no tree pointer.
  - The post says "All source control data is content-addressed". Its id is the SHA-256 of its canonical bytes; the
    hash, the encoding and the fields besides parents and changes are **[Lite assumptions]**.
  - Changesets are kept in the Spanner `changesets` table **[Lite assumption]**. The post calls a changeset "an
    entry in a write-ahead log" but does not say where that log lives.
- **Blobs and trees** (`objects`): content-addressed, and stored compressed in cloud storage. Deflate, and keeping
  trees there, are **[Lite assumptions]**.
- **Root tree** (derived data): the full listing of a changeset's files.
  - It is computed from the first parent's root tree by `tree.TreeEdit`, which rewrites only the directories on
    changed paths.
  - By default it is computed eagerly when the changeset is created. With deferral, it is computed the first time
    someone asks for it. Either way it is written back to Spanner's `root_tree` table.
  - It is computed iteratively, walking back to the nearest known ancestor.
- **Refs**:
  - A row in the Spanner `refs` table.
  - Moving a ref is a compare-and-swap on that one row, and it is a separate step from creating changesets.
  - A ref only moves forward: the new target must descend from the changeset it points at **[Lite assumption]**.

## How the operations work

- **Commit.** `scn.commit` sends the tracked edits to a server, which creates the changeset there. As the post
  says, "every commit, rebase, or merge happens on the server, so there's nothing to push". The server writes in a fixed order:
  1. Blobs and trees, durably, in cloud storage.
  2. The changeset row in Spanner.
  3. The `root_tree` row.
  4. The reply.

  So no changeset ever points at missing content. This order is a **[Lite assumption]**. No locks are taken, and
  no other server is involved.
- **Fetch.** scn starts from the root tree, then asks for the trees it is missing, one level at a time, then the
  blobs. It never tells anyone what it already has. Repeated requests are answered by the daemon and cache.
- **Checkouts.** There are three kinds:
  - Full.
  - Sparse: only the chosen directories' trees and files.
  - Virtual: nothing is fetched up front, and each file is fetched when it is first read.
- **Server operations.** `getObject`, `getChangeset`, `getRootTree`, `log`, `diff` (which skips subtrees whose
  ids are equal), `mergeBase`, `rebase` and `merge`.
  - `mergeBase` returns a best common ancestor, as git's merge-base does: a common ancestor that no other common
    ancestor descends from. The first common ancestor a walk meets is not always one, and merging from an older
    one would undo changes made since. After a criss-cross merge there can be several; Scion Lite picks one, where
    git's recursive strategy merges them **[Lite assumption]**.
  - Rebase replays only the paths each changeset recorded. Files that both sides changed are merged line by line,
    and real conflicts are reported.
- **Land.** `scn.land(branch)`:
  1. Rebase onto the branch tip, on the server.
  2. Compare-and-swap the branch from that tip to the result.
  3. If someone else moved the branch first, retry.
  4. On a conflict, stop.

  `scn.landChangeset(branch, id)` does the same for a changeset made with `createChangeset`, with no working copy:
  "many write workloads can also skip the checkout and function entirely via API".

  There is no global lock. The post mentions an Antique merge queue but doesn't describe how landing works, so this
  loop stands in for it **[Lite assumption]**.
  - A compare-and-swap whose reply is lost may still have happened, so scn remembers unconfirmed targets. A later
    attempt checks for them first and rebases only what came after.

## Packages

| Package | Contents |
|---|---|
| `objects` | `ObjectId`, `Blob`, `Tree`, `TreeEntry`, `ObjectCodec`, `ObjectStore` |
| `changeset` | `Changeset`, `FileChange`, `Paths`, `Compression` |
| `tree` | `TreeEdit` (incremental root tree), `TreeWalker` (level-by-level loading, lookup, diff) |
| `diff`, `merge` | Myers line diff, unified diff, diff3 text merge (`TextMerge`), flat-snapshot `TreeMerge` |
| `rpc` | One request/reply envelope over tickloom; `RpcNode` base process |
| `api` | `ScionApi`: the calls every tier answers |
| `server`, `cache`, `daemon`, `scn` | The tiers |
| `spanner`, `storage` | The Spanner and cloud-storage nodes; `DurableTables` and `Journal` on the simulated disk |
| `topology` | Process ids, who talks to whom, per-tier timeouts |
| `async` | `Futures`: `allOf` and a stack-safe asynchronous loop |

## Tests

Every test except the unit tests builds the full set of tiers with `sim.ScionSimulation`. Each of those tests
begins with a comment describing its scenario step by step.

| Test | What it shows |
|---|---|
| `tiers.TierChainTest` | One call travels through every tier to Spanner and back. |
| `showcase.AgentScaleScenarioTest` | **The realistic scenario.** Agents and jobs, as in the post: a sparse checkout, a virtual checkout served by the regional cache, a bot that writes and lands with no checkout, two jobs on one node sharing its daemon, three concurrent landings merged on the server, a conflict fixed and landed, and a post-merge CI job that asks the server for the merge base and diff and reads only the changed files. |
| `showcase.ShowcaseTest` | **Start here.** One story through the whole system: seed and land main, a checkout served by the regional cache, a virtual checkout, commits created on the server, two concurrent landings merged on the server, and a third workstation reading the result. |
| `spanner.SpannerTest` | Spanner's API on its own: strong reads by key, range and `all`; one transaction per session, and a restart deletes sessions; `ALREADY_EXISTS` and `NOT_FOUND` fail a whole commit; a transaction whose read row changed is `ABORTED`, then retried; committing again returns the same outcome; rollback and idle expiry; a crash keeps all of a commit or none; commit timestamps increase across a restart. |
| `server.ChangesetTest` | A commit is created on the server and records only the files that changed. |
| `server.ServerOperationsTest` | Unchanged files are dropped; root trees are derived on demand and written back, including over a 150-changeset history; log, diff, merge base (the best common ancestor, not the nearest), merge and rebase; ref compare-and-swap, including two servers' overlapping transactions on one ref; refs only move forward. |
| `tiers.TiersTest` | A second workstation is served entirely from the regional cache; the daemon answers repeats; the daemon's cache is bounded; the cache and server answer the same calls as the daemon. |
| `scn.CheckoutTest` | The objects fetched by full, sparse and virtual checkouts; commit; nothing to push. |
| `scn.LandingTest` | First landing; three concurrent landings all land; edits to different lines merge; conflicts stop a landing. |
| `failures.FailureTest` | Spanner and cloud storage crash and restart; a server crash (another serves, and a restarted server starts empty); a partition between cache and servers; write order (a changeset is recorded only after its content is durable); a server replacing its Spanner sessions after Spanner restarts. |
| `randomized.RandomizedLandingTest` | Four workstations in two regions land concurrently with 5% packet loss on every link, over 10 seeds. Every landing reported as successful is in main. |
| unit tests | Changeset encoding and ids, compression, incremental tree edit, object codec, diff, merge, journal. |

## What Scion Lite leaves out

- Antique (the code review tool) and its merge queue.
- IAM and per-directory access control.
- The daemon's periodically refreshed snapshot.
- Streaming objects in the daemon's disk format.
- Derived data other than root trees: blame, path history, commit-graph skip lists, copy detection and path search.
  The post computes all derived data "through one generic mechanism"; Scion Lite has only the root tree.
- Without skip lists, `log`, `mergeBase` and `rebase` walk the history, so their cost grows with history size.
- A working copy on disk and a filesystem watcher: scn keeps the working copy in memory.
- Clients outside the fleet: every Scion Lite node has a daemon.
- Spanner's and cloud storage's internal replication.
- Protection against a failed regional cache overwhelming the servers, which the post names as a goal.
- The 99% cache hit rate is not measured.

As the post says, "Scion is by no means a suitable replacement for git for general use"; neither is Scion Lite.
TODO.md lists every difference from the post in one place.

## Names

The post names no RPCs or messages. Every Scion call and message name (`createChangeset`, `RebaseRequest`,
`MoveRefReply`, ...) is Scion Lite's own **[Lite assumption]**; the operations behind them come from the post (see
`mapping.md`). The Spanner calls are the exception: their names come from Google's Spanner reference. The client
machines in the tests (alice, bob, carol, dave) are made-up names.

The post describes a single monorepo, so Scion Lite has one repository and no call that creates one.
