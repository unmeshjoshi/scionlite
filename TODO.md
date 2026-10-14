# Scion Lite: TODO (redesign to follow the Scion post)

Source: "Redesigning source control at Anthropic" (anthropic.com/engineering/redesigning-source-control).
Scion Lite models only what the post describes. Where the post is silent, the choice is labelled
**[Lite assumption]** in the code and in `mapping.md`.

## Decisions (from the user)
- Model every tier: spanner, cloud-storage, scion-server, scion-cache, scion-daemon, scn.
- Replace the git-style design: client-side history, push, and Raft-style replication.
- Derived data: the root tree of each changeset only.

## 1. Remove the git-style design
- [x] Delete the replicated push server, the git-style local `Repository`, and its protocol and tests
- [x] Keep `objects` (ObjectId, Blob, Tree, TreeEntry), `diff`, `merge` (TextMerge, TreeMerge) and `storage.Journal`

## 2. Data model
- [x] `Changeset`: parents, author, time, message, and the files changed relative to the **first parent**
      (path to blob id, or deleted). No tree pointer. Content-addressed id **[Lite assumption]**
- [x] Blobs are kept compressed in cloud storage
- [x] Incremental tree edit: apply a changeset's changes to the parent's root tree, rewriting only the
      directories on changed paths (cost grows with the size of the change, not the repo)

## 3. RPC plumbing
- [x] One request/reply envelope, `RpcRequest(method, body)` / `RpcReply(success, error, body)`, over tickloom
- [x] `RpcNode` base process: `serve(method, body)` and `call(target, method, request)`
- [x] `ScionApi`: the RPCs that daemon, cache and server all answer

## 4. Spanner (one node; real Spanner runs Paxos per split; same API)
- [x] Tables of rows: read, insert-if-absent, write, compare-and-swap, scan by prefix
- [x] Durable on the simulated disk (Journal); reply only once durable
- [x] Tables used: `refs`, `changesets` (the changeset write-ahead log **[Lite assumption: kept in Spanner]**),
      `root_tree` (derived data)

## 5. Cloud storage (one node)
- [x] Put and get immutable objects by id; durable on the simulated disk

## 6. scion-server (several nodes, no long-lived state)
- [x] Caches immutable objects, changesets and derived data in memory
- [x] `getObject`, `getChangeset`, `getRootTree` (derived recursively from the first parent; computed on demand,
      cached, written back to Spanner)
- [x] `createChangeset` on the server: stores blobs, records the changeset, computes the root tree eagerly
- [x] `resolveRef`, `listRefs`, `moveRef` (compare-and-swap on one Spanner row, separate from changesets)
- [x] `log`, `diff`, `mergeBase`
- [x] `merge` and `rebase` on the server, without locks; conflicts are reported

## 7. scion-cache (regional) and scion-daemon (one per workstation)
- [x] Same RPCs as the server. Immutable objects are served from cache; everything else goes upstream
- [x] Strict tiers: daemon → its region's cache → servers (spread by object id)
- [x] Daemon keeps a **bounded** cache (LRU); the cache keeps everything in memory
- [x] Hit and miss counters

## 8. scn client
- [x] Talks only to its node's daemon
- [x] Fetch protocol: starts from a root tree and asks for missing trees, then blobs. It never says what it has
- [x] Sparse checkouts (chosen directories only) and virtual checkouts (files fetched when first read)
- [x] Working-copy edits tracked by the client (the post's client "monitors for filesystem changes")
- [x] `commit` = `createChangeset` on the server: nothing to push
- [x] `land(branch)`: rebase onto the tip, then compare-and-swap the branch; retry if someone moved it,
      stop on conflicts. No global merge-queue lock

## 9. Tests (simulated network and simulated disks)
- [x] Unit: changeset encoding, incremental tree edit, compression
- [x] Spanner: compare-and-swap rules, durability across a crash
- [x] Cloud storage: durability across a crash
- [x] Server: create changeset, root tree derived on demand and written back, log, diff, merge base, merge, rebase
- [x] Tiers: a second workstation in the same region is served from the cache; the daemon's cache is bounded;
      every tier answers the same RPCs
- [x] Servers keep no long-lived state: crash a server and another serves; a restarted server starts empty
- [x] Sparse checkout fetches only the requested trees; virtual checkout fetches a blob on first read
- [x] Concurrent landings: both land after a rebase; conflicting edits report a conflict
- [x] Spanner crash and restart: refs and changesets survive
- [x] Partition between cache and server: requests time out; recovery after healing
- [x] Randomized: several workstations land at once over a lossy network; every landed changeset is in main

## 10. Docs
- [x] README and mapping.md rewritten; each [Lite assumption] listed


## Status
All items done; `./gradlew test` runs 71 tests.

## Differences from the post
Checked against the post's text twice. Quoted phrases in the code and docs are verbatim from the post.

### Where Scion Lite departs from what the post says
- **Bounded object cache.** The post: "The client maintains a bounded local object cache". Scion Lite puts the bounded
  (LRU) cache in the daemon, and scn keeps no object store of its own.
- **Daemons everywhere.** The post describes daemons "In the fleet". Every Scion Lite node (called a workstation)
  has one.
- **Working copy.** The post's client "lays out the working copy files on disk, monitors for filesystem changes".
  scn keeps the working copy in memory, and edits go through `write` and `delete`.
- **Spanner and cloud storage are single nodes.** The post doesn't say how either is built. It never mentions Raft,
  Paxos or consensus; Paxos replication comes from Google's Spanner paper, not the post.
- **Derived data.** The post computes all derived data "through one generic mechanism", with blame, path history,
  commit-graph skip lists, copy detection and path search. Scion Lite has one hand-written kind: the root tree.
- **Ancestry cost.** With no skip lists, `log`, `mergeBase` and `rebase` walk the history, so they cost more as
  history grows. The post's requirement that cost "scale with the size of the change" is met by commit creation
  (`TreeEdit`), not by these calls.
- **Cache hit rate.** The post: each tier "maintains a cache hit rate of over 99%". Scion Lite counts hits but
  doesn't measure or target a rate.
- **Failure isolation.** The post wants to avoid "a failure in a regional scion-cache" overwhelming the servers.
  Scion Lite has no load shedding or protection of that kind.

### [Lite assumptions] (the post is silent)
- Changesets are kept in the Spanner `changesets` table (the post: "an entry in a write-ahead log").
- Changeset ids are the SHA-256 of an encoding of our own (the post: "All source control data is content-addressed").
  The fields besides parents and changes (author, time, message) are ours too.
- Author and time come from the client, so a retried create names the same changeset.
- Trees are stored in cloud storage, compressed with Deflate.
- Server write order: content made durable in cloud storage, then the changeset row, then the root tree row, then
  the reply.
- Caches also cache changesets and root trees, and pass every write through to a server.
- A cache picks a server by a hash of the request, and skips one that timed out for a while.
- Each tier waits for a reply for less time than the tier above it (`topology.Timeouts`).
- Landing is a loop in scn: rebase on the server, compare-and-swap the branch, retry, stop on conflict. The post
  mentions an "Antique merge queue" but doesn't describe landing.
- scn remembers landings it couldn't confirm, and rebase takes `since` to replay only later changesets.
- Rebase drops a replayed changeset that turns out empty.

### Not modelled
- Antique and its merge queue, and Claude fixing conflicts.
- IAM and per-directory access control.
- "Each daemon starts from a periodically refreshed snapshot".
- Caches that "stream objects directly from memory, in the disk format that the daemon will consume".
- Server answers about file history and blame.

## Failure scenarios that tickloom makes possible
Tickloom runs every process in one deterministic tick loop, on a simulated network, with a simulated disk for each
process. That lets the tests create failures that are hard or impossible to cause on demand, or to replay exactly,
on real machines.

### Used by the tests
- **A power cut at an exact tick** (`Cluster.crashNode`). The process stops, and its disk keeps only what was
  synced: unsynced journal writes are lost. This shows Spanner and cloud storage reply only after data is durable
  (`FailureTest`).
- **Restart as a new, empty process on the same disk** (`Cluster.restartNode`). Spanner and cloud storage recover
  from their journals; a restarted server starts with an empty memory (`FailureTest`, `ServerOperationsTest`).
- **Partitions between chosen processes** (`partitionNodes`, `healPartition`):
  - Cache cut off from every server: calls fail, while immutable data the daemon and cache already hold is still
    served.
  - Servers cut off from Spanner partway through a commit: the blob is already durable in cloud storage, but no
    changeset row exists. This is the write-order test.
- **Seeded packet loss on every link** (`setPacketLoss`). In `RandomizedLandingTest`, a compare-and-swap that took
  effect but whose reply was lost happens on particular seeds, every time those seeds run. That is how the
  false-conflict bug in `land` was found and then checked fixed.
- **Exact replay.** A failing seed reproduces the same run: same message order, same losses, same timeouts.
- **Time in ticks.** Timeouts and changeset times come from simulated clocks, not wall-clock time.

### Available in tickloom, not used yet
- One-way partitions (`partitionOneWay`), for example a server that can send to Spanner but not hear back.
- Dropping one message type, or only the Nth message (`dropMessagesOfType`, `dropNthMessagesOfType`). This could
  drop exactly the reply to a `moveRef`, instead of waiting for a seed that does it.
- Per-link delay (`setNetworkDelay`), to make one server slow rather than down.
- Clock skew per process (`setTimeForProcess`). Changeset times come from the client, so this would show that skew
  changes ids but not correctness.

### Failures that come from the simplified model, not from Scion
- Spanner and cloud storage are single nodes, so crashing either one stops the whole system until it restarts. The
  real services replicate.
- A crash losing unsynced writes matters only because each service is a single disk.
- At high loss (15% per link), calls fail with "Request expired" timeouts. Every call crosses four tiers and each
  tier has a fixed timeout (a [Lite assumption]). No run gave a wrong result, only no result.
