# Scion Lite

Scion Lite is a small teaching version of a distributed source control system, built on
[tickloom](https://github.com/unmeshjoshi/tickloom). Its name comes from Scion, Anthropic's in-house source control
system ([Redesigning source control at Anthropic](https://www.anthropic.com/engineering/redesigning-source-control)).
It is not that system and does not reproduce its design. [`mapping.md`](mapping.md) shows how each piece
corresponds to a Scion component, and where it differs.
Every component runs in tickloom's single-threaded tick loop. That makes the whole system — replicas, network,
disks and developer workstations — deterministic to simulate. The tests run on tickloom's **simulated network** and
**simulated filesystem**, with crashes, partitions and packet loss.

## Build and test

Depends on `io.github.unmeshjoshi:tickloom` and `tickloom-testkit` 0.1.0-beta.4 from Maven Central.

```bash
./gradlew test
```

## Model

| Concept   | What it is |
|-----------|------------|
| Blob      | The bytes of a file |
| Tree      | A directory: sorted `(file|dir, id, name)` entries |
| Commit    | Root tree, parents (0, 1, or 2 for a merge), author, timestamp from the process clock, message |
| ObjectId  | SHA-256 of `"<type> <length>\0<content>"`, so equal content has an equal id |
| Branch    | A name pointing at a commit. Locally `heads/<b>`; the server's last known state is `remotes/origin/<b>` |

## Packages

| Package    | Responsibility |
|------------|----------------|
| `objects`  | Object model, canonical encoding, in-memory content-addressed `ObjectStore` (with read-through overlays) |
| `snapshot` | Flat `path → bytes` ⇄ nested trees, with path validation |
| `history`  | `CommitGraph`: log, ancestry, merge base, objects to send (reachable from tips minus reachable from haves), missing objects |
| `diff`     | Myers line diff, unified diff output, tree diff |
| `merge`    | diff3 three-way text merge with conflict markers; three-way tree merge |
| `storage`  | `Journal`: durable append-only records on a tickloom `FileSystem` (tickloom `Wal` + `BatchedLog`) |
| `repo`     | `Repository`: working tree, index, status, commit, branches, checkout, diff, merge, clone/fetch/pull/push |
| `protocol` | Message records exchanged by clients and replicas |
| `server`   | `ScionServer`: a tickloom `Replica` that replicates pushes |
| `client`   | `ScionClient`: a tickloom `ClusterClient` |

## Local repository

- History and refs are durable. Each change is one record in the repository's journal, and a command's future
  completes only once that record is synced.
- The working tree and the index are in memory, like an editor's buffers. After a crash the repository reopens
  with them reset to the last commit of the current branch.
- `merge` fast-forwards when it can and commits a clean three-way merge at once. A conflicted merge leaves
  markers in the working tree; the user resolves them, runs `add`, then `commit` (which records two parents).
- `push` sends only the objects the server lacks (reachable from the branch, minus reachable from the
  remote-tracking refs). It expects the server's branch to be at the remote-tracking value (compare-and-set).

## Replication

Replication uses a log with a **fixed primary**: the replica whose id sorts first.

1. The primary checks each push against the branches as they stand after every accepted push:
   - the expected old value must match (otherwise `STALE`)
   - the update must be a fast-forward unless forced (otherwise `NON_FAST_FORWARD`)
   - every reachable object must be present (otherwise `MISSING_OBJECTS`)

   If the push passes, it is assigned the next log index and appended to the primary's journal.
2. Only after the entry is **durable on the primary** is it sent to the followers. So every follower's log is a
   prefix of the primary's durable log. A primary crash can never lose an entry that a follower holds.
3. Followers append entries in index order and acknowledge the highest index durable on their disk.
4. An entry is **committed** once a majority, counting the primary, has it durable. The push is acknowledged then,
   and the branch moves for readers.
5. Heartbeats resend unacknowledged entries and carry the commit index. A partitioned or restarted follower catches
   up with no extra protocol.
6. A restarted primary doesn't know which of its entries were committed. It holds client requests until it has
   committed its whole recovered log again, so it never shows a branch older than one it showed before.
7. A push retried after a timeout may come back `STALE` with the branch already at the pushed commit. The client
   treats that as success.

Limitations, by design for now:
- There is no failover: while the primary is down, pushes and primary reads are unavailable.
- Reads from followers (`fetchFrom`) are served from what that follower knows is committed, which can be behind.
- Logs are never compacted, and every object is held in memory.

## Tests

| Suite | Covers |
|-------|--------|
| `ObjectCodecTest`, `SnapshotsTest`, `CommitGraphTest`, `DiffTest`, `MergeTest` | Pure core |
| `JournalTest` | Durability on the simulated disk: synced records survive a crash, unsynced ones do not |
| `LocalRepositoryTest` | Commit, status, branches, checkout, fast-forward / three-way / conflicted merge, crash recovery |
| `ScionReplicationTest` | Clone/push/fetch end to end, concurrent pushes (one wins, the other pulls, merges and pushes), non-fast-forward and force, incomplete and invalid pushes, follower partition and catch-up, no majority, stale follower reads, follower crash, primary crash with held reads, workstation crash, feature branches |
| `ScionRandomizedTest` | Three developers pushing to one branch for several rounds over a lossy, delayed network while a follower crashes. Checks that no acknowledged push is lost and all replicas converge. Runs with five seeds |

See `TODO.md` for the work breakdown.
