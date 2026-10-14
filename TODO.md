# Scion — TODO

Scion is a distributed source control system built on tickloom. Every component runs in the tickloom
single-threaded tick loop, and every test runs on the simulated network and simulated filesystem.

## 0. Project setup
- [x] Gradle project depending on `tickloom:0.1.0-beta.4` and `tickloom-testkit:0.1.0-beta.4` (mavenLocal)
- [x] README with architecture and usage

## 1. Object model (pure, no I/O)
- [x] `ObjectId` — SHA-256 of the object's canonical bytes, hex encoded
- [x] `Blob`, `Tree` (sorted entries: name, kind FILE/DIR, id), `Commit` (tree, parents, author, timestamp, message)
- [x] `ObjectCodec` — canonical encoding `"<type> <len>\0<content>"`; decode back; id is stable across runs
- [x] `ObjectDatabase` (read) / in-memory implementation
- [x] Tests: ids are content addresses, round trip, tree order does not depend on insertion order

## 2. Snapshots
- [x] Build nested trees from a flat `path -> bytes` map (directories become trees)
- [x] Read a tree back into a flat map
- [x] Reject invalid paths (empty segments, `.`/`..`, absolute)
- [x] Tests: identical content gives identical root ids, nested dirs, round trip

## 3. History
- [x] `CommitGraph`: log (newest first), `isAncestor`, merge base (best common ancestor), objects reachable from
      a set of commits minus objects reachable from another set (for fetch/push negotiation)
- [x] Tests: linear history, branches, criss-cross merge base, reachability excludes "haves"

## 4. Diff
- [x] Line diff (Myers / LCS) with unified-diff output
- [x] Tree diff: added / modified / deleted paths between two snapshots
- [x] Tests

## 5. Merge
- [x] Three-way line merge (diff3) with conflict markers
- [x] Three-way snapshot merge: per-path rules for add/delete/modify, conflict list
- [x] Tests: clean merges, conflicting edits, delete/modify conflict, identical edits

## 6. Durable storage on the tickloom filesystem
- [x] `RepositoryJournal`: write-ahead log (tickloom `Wal` + `BatchedLog`) of records; recover on open
- [x] Records are acknowledged only when durable
- [x] Tests on `SimulatedFileSystem`: survives a crash after sync, loses unsynced writes, torn tail ignored

## 7. Local repository (client side)
- [x] Working tree (in memory), `add`/`rm`, `status`, `commit` (durable before the id is returned)
- [x] Branches: create, list, switch (checkout), delete; HEAD
- [x] `log`, `diff` (working tree vs HEAD, commit vs commit)
- [x] Local `merge` (fast-forward, three-way, conflicts reported, merge commit with two parents)
- [x] Remote-tracking refs (`origin/<branch>`)
- [x] Reopen from disk after a crash: committed history and branches survive
- [x] Tests on simulated filesystem

## 8. Replicated server (tickloom `Replica`)
- [x] Fixed primary + followers. Primary assigns each accepted push a log index, makes it durable locally, then
      replicates it; a push is acknowledged once a majority has it durable
- [x] Ref updates are compare-and-set (expected old value) and fast-forward only unless forced
- [x] Pushes must be complete: every object reachable from the new tips is in the push or on the server
- [x] Followers accept entries only in order; gaps are filled by the primary (heartbeats carry commit index,
      followers report their last index)
- [x] Primary serves requests only after it has re-committed its recovered log (no stale reads after restart)
- [x] Fetch: list refs + objects reachable from wanted refs minus objects the client already has
- [x] Recover from the WAL on restart
- [x] Messages and message types

## 9. Remote client (tickloom `ClusterClient`)
- [x] `listRefs`, `fetch`, `push`
- [x] Local repo operations `clone`, `fetch`, `pull` (fetch + merge), `push`

## 10. Distributed tests (simulated network + simulated filesystem)
- [x] Clone, commit, push, second user clones and sees the history
- [x] Concurrent pushes to the same branch: one wins, the other is rejected as non-fast-forward; it pulls,
      merges and pushes again
- [x] Push succeeds with one follower partitioned; the follower catches up after the partition heals
- [x] Push fails (times out) when the primary cannot reach a majority, and the ref is not changed
- [x] Follower crash and restart: recovers its log from disk and catches up
- [x] Primary crash and restart: acknowledged pushes survive; reads wait until the log is re-committed
- [x] Client crash: committed local history survives on the client's simulated disk
- [x] Incomplete push (missing objects) is rejected
- [x] Randomised scenario with message loss: all replicas converge on the same refs
