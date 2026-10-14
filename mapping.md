# Mapping Scion Lite to Scion

Scion is Anthropic's in-house source control system. It replaced git for Anthropic's monorepo. It is described in
the engineering post [Redesigning source control at Anthropic](https://www.anthropic.com/engineering/redesigning-source-control)
(October 2026).

**There is no public Scion source code.** The post describes Scion's internals as bespoke and does not release them.
So this file maps Scion Lite's packages and classes to the **components and ideas the post names**, not to files in a
repository. Where Scion Lite takes a simpler or different approach, the table says so.

Scion Lite source root: `src/main/java/com/versioncontrol/scion/`.

## Components

| Scion (from the post) | What it does there | Scion Lite | Notes on the difference |
|---|---|---|---|
| **scn** (the client) | Command-line client; talks to scion-daemon over a socket | `repo.Repository` (local commands) + `client.ScionClient` (network) | No command-line interface; the API is called from tests. The client talks to the servers directly, with no daemon in between. |
| **scion-daemon** | Per-node object store; bounded local cache; watches the filesystem; serves virtual, sparse checkouts | `repo.Repository` with its `objects.ObjectStore` and `storage.Journal` | Lite keeps a full local copy of the history (git-style) and an in-memory working tree. There is no cache bound, no sparse or virtual checkout and no filesystem watching. |
| **scion-cache** | Regional cache tier for trees and blobs; falls back to the servers when it misses | — (not modelled) | `ScionClient.fetchFrom(replica, …)` reading from a follower is the closest thing: a read that may be behind. Content-addressed objects make any replica safe to read objects from, which is the same property Scion's caches rely on. |
| **scion-server** | Authoritative tier. Computes derived data, and runs commits, merges and rebases on the server | `server.ScionServer` (a tickloom `Replica`) | Lite servers only accept pushes and serve fetches. Commits and merges happen on the client, git-style. |
| **Spanner** (changeset metadata, refs, derived data) | Globally consistent store; a ref update is a compare-and-swap on a single row | `ScionServer`'s replicated log: a fixed primary, a majority quorum, and a `Journal` on each replica | Lite builds its own consistency (Raft-like, but with no leader election) in place of a managed database. The compare-and-swap on a ref is the same idea: `RefUpdate.expectedOld`, and `PushStatus.STALE` when it does not match. |
| **Cloud storage** (blobs) | Holds file contents | The `objects` inside `protocol.LogEntry`, stored in each replica's `Journal` | Lite replicates blobs through the same log as ref updates, and every server holds all of them in memory. |
| **Antique** (code review, merge queue host) | Review tool | — | Not modelled. |
| Source control IAM | Access control | — | Not modelled. |

## Data model

| Scion | Scion Lite | Notes |
|---|---|---|
| **Changeset**: the files changed relative to the first parent, plus content-addressed blob pointers. Stored as entries in a write-ahead log. **Does not point to a tree.** | `objects.Commit` | **Different.** A Lite commit points to a full root `Tree`, as in git. The nearest thing to a changeset is `diff.TreeDiff` between a commit and its first parent, which is computed when needed rather than stored. |
| Changesets kept in a **write-ahead log** | `protocol.LogEntry` in `server.ScionServer`, written with tickloom `Wal`/`BatchedLog` through `storage.Journal` | Similar: Lite's server keeps a write-ahead log of accepted pushes. |
| Immutable, content-addressed **trees and blobs** | `objects.Tree`, `objects.Blob`, `objects.ObjectId` (SHA-256), `objects.ObjectCodec` | Same idea. |
| **Refs**: few of them (main and release branches); compare-and-swap, and separate from creating changesets | `protocol.RefUpdate`, the `committedRefs` / `acceptedRefs` maps in `ScionServer`, and `repo.RefNames` | Lite allows any number of branches. A Lite push carries its objects and its ref updates together in one log entry. Scion creates changesets separately from moving refs. |
| **No packfiles**, so no repository maintenance | Objects are stored one by one in journal records | Lite has no packfiles either, but its journal is never compacted. |

## Derived data and server-side operations

| Scion | Scion Lite | Notes |
|---|---|---|
| Merge base, as a server-side request | `history.CommitGraph.mergeBase` | Runs on the client. |
| Diffs, as a server-side request | `diff.LineDiff` (Myers), `diff.UnifiedDiff`, `diff.TreeDiff`, `Repository.diff` | Runs on the client. |
| Blame and path history | — | Not implemented. |
| Commit-graph skip lists | `CommitGraph.ancestors` / `isAncestor` | Lite walks the graph each time, with no precomputed index. |
| Copy detection and path-based search | — | Not implemented. |
| Server-side commits, merges and rebases, with no locks | `Repository.commit` / `merge`, `merge.TextMerge` (diff3), `merge.TreeMerge` | Lite merges on the client and resolves conflicts there, then pushes. The server serialises pushes through the primary's log. |
| Concurrent changes may conflict; **Claude** usually resolves them | `PushStatus.STALE` → `Repository.pull` → resolve → `push` | Lite's randomized test resolves conflicts with a simple keep-both-sides rule. |

## Fetch protocol

| Scion | Scion Lite | Notes |
|---|---|---|
| Negotiates only the trees and blobs the client **wants** (sparse); it does not need to know what the client has | `protocol.FetchRequest(haves)` → `CommitGraph.objectsToSend(tips, haves)` | **Different.** Lite fetch is git-style: the client lists the commits it has, and the server sends everything reachable from the branches minus what is reachable from those commits. |
| The daemon, cache and server tiers share the same requests, so the client does not need to know the cache layout | `ScionClient.fetch` / `fetchFrom` | Lite has a single tier of replicas. |

## Things only Scion Lite has, for teaching

- **Deterministic simulation.** Every component runs on tickloom's tick loop. `src/test/.../sim/ScionSimulation` runs the replicas and workstations over a simulated network and simulated disks, with partitions, packet loss, crashes and restarts.
- **An explicit replication protocol** (`AppendEntries` / `AppendResponse`, majority commit, a restarted primary holds reads until it is caught up). This makes visible what Scion delegates to Spanner.
