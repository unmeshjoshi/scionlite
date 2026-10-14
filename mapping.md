# Mapping Scion Lite to Scion

Scion is Anthropic's in-house source control system for its monorepo. It is described in the engineering post
[Redesigning source control at Anthropic](https://www.anthropic.com/engineering/redesigning-source-control)
(October 2026).

**There is no public Scion source code.** This file maps Scion Lite's packages and classes to the **components and
ideas the post names**, not to files in a repository. Each row says what the post states. When Scion Lite has to
choose something the post leaves out, the row is marked **[Lite assumption]**. Things the post describes that
Scion Lite does not build are listed under "Not modelled".

Source root: `src/main/java/com/versioncontrol/scion/`.

## Components

| Scion (from the post) | Scion Lite | Notes |
|---|---|---|
| `scn`, the client | `scn.Scn` | Talks only to its node's daemon over a socket; here, the simulated network. |
| `scion-daemon`: "In the fleet, the client talks over a socket to a daemon, scion-daemon, that stores objects for the whole node" | `daemon.ScionDaemon` | **[Lite assumption]** Every node (called a workstation here) has a daemon; the post describes daemons in the fleet. The post says "The client maintains a bounded local object cache". **[Lite assumption]** Scion Lite puts that bounded cache in the daemon: a count of immutable replies, evicted least recently used first. scn keeps no object store of its own. |
| `scion-cache`, regional: "When a cache is missing an object, it asks the authoritative scion-servers" | `cache.ScionCache` | **[Lite assumption]** It spreads calls over servers by a hash of the request, and skips a server that timed out for a while. The post calls caches "the tree and blob cache servers"; **[Lite assumption]** they also cache changesets and root trees ("All source control data is content-addressed, so ... any server can cache it"), and pass every write through to a server. |
| `scion-server`, authoritative, "essentially large caches that can also compute derived data" | `server.ScionServer` | Keeps immutable data in memory only; a restarted server starts empty. The post: each tier "does not require long-lived state". Its "cache hit rate of over 99%" is not measured here. |
| "The scion-daemon, scion-cache, and scion-server all respond to identical RPCs for this protocol" | `api.ScionApi` | All three implement `RpcNode.serve` for the same method names. |
| Spanner | `spanner.Spanner`, `spanner.SpannerApi`, `spanner.Schema` | **One node**, durable through a journal. Its calls are `CreateSession`, `Read`, `BeginTransaction`, `Commit` and `Rollback` from Google's `google.spanner.v1` RPC reference, not from the post (see the README). The post does not mention Raft, Paxos or consensus; Paxos replication comes from Google's Spanner paper, not the post. |
| "the compressed file contents in blob storage"; servers "read from Spanner or cloud storage as needed" | `storage.CloudStorage`, `changeset.Compression` | One node; first write wins. **[Lite assumption]** Deflate. |
| "Any tier could theoretically talk to any other, but we strictly enforce the tiering to isolate failure domains" | `topology.Topology` | Who may call whom. `TiersTest` connects test clients straight to a cache and a server only to show they answer the same calls. |
| The post names no RPCs | `api.ScionApi` names (`createChangeset`, `RebaseRequest`, ...) | **[Lite assumption]** Every Scion call and message name is Scion Lite's own. Only the operations come from the post. |
| — | `topology.Timeouts` | **[Lite assumption]** Each tier waits less than the tier above it, so a cache can try another server before the client gives up. |

## Data model

| Scion (from the post) | Scion Lite | Notes |
|---|---|---|
| A changeset is "an entry in a write-ahead log" | Rows of the Spanner `changesets` table | **[Lite assumption]** The post does not say where the log lives. |
| "a changeset stores a list of the files that changed relative to its first parent" | `changeset.Changeset.changes()`, `changeset.FileChange` | Each change is a path and a blob id, or a deleted file. There is no tree pointer. |
| "content-addressed pointers to the compressed file contents in blob storage" | `FileChange.blob`, `objects.ObjectId` (SHA-256) | |
| "All source control data is content-addressed" | `Changeset.id()`, the SHA-256 of `encode()` | **[Lite assumption]** The hash and encoding. Content addressing makes a retried create idempotent. |
| "A changeset stores most of what you'd expect from a commit in git" | `Changeset` parents, author, time, message | **[Lite assumption]** The exact fields. |
| Author and time | `CreateChangesetRequest.author`, `.time` | **[Lite assumption]** Supplied by the client, so a retry names the same changeset. |
| Trees | `objects.Tree`, stored in cloud storage | **[Lite assumption]** The post mentions root trees and recursive tree fetches but not where trees are stored. |
| Refs are discouraged "except for the main ref or release branches" | Rows of the Spanner `refs` table | |
| "moving one is a compare-and-swap on a single Spanner row that happens separately from creating changesets" | `ScionServer.moveRef` | Spanner has no compare-and-swap call, so the server runs a read-write transaction on the ref's row: `Read` (beginning the transaction), compare, then `Commit` or `Rollback`, retrying on `ABORTED`. **[Lite assumption]** A ref only moves forward: the target must descend from `expected`. |

## Derived data and server-side operations

| Scion (from the post) | Scion Lite | Notes |
|---|---|---|
| "Each type of derived data is defined recursively, so its value for a changeset is computed from its values for that changeset's parents" | `ScionServer.rootTree`, `tree.TreeEdit` | Only the root tree is built. It is the parent's root tree with the changeset's files applied, rewriting only the directories on changed paths. |
| "we can choose whether to compute it eagerly or defer the work"; servers "write the results back to Spanner" | `ScionServer.DerivedData` (EAGER, DEFERRED), the `root_tree` table | A missing root tree is computed by walking back to the nearest known one, in a loop rather than recursion. |
| Changesets are accepted with "no locking or cross-server coordination" | `ScionServer.createChangeset`, `persist` | **[Lite assumption]** Write order: content made durable in cloud storage, then the Spanner row, then the root tree, then the reply. |
| "every commit, rebase, or merge happens on the server" | `ScionServer.createChangeset`, `rebase`, `merge` | Rebase replays only the recorded paths. Text merge is diff3 (`merge.TextMerge`). |
| Servers provide APIs "such as getting changesets, computing diffs, finding merge bases, and fetching blobs" | `getChangeset`, `diff`, `mergeBase`, `getObject`, plus `log`, `getRootTree`, `listRefs`, `resolveRef` | `diff` skips subtrees whose ids match. `mergeBase` returns a best common ancestor (one no other common ancestor descends from), as git's merge-base does. **[Lite assumption]** After a criss-cross merge it picks one of several, nearest to the second argument; git's recursive strategy merges them. |
| Rebase onto a changed base | `RebaseRequest.since` | **[Lite assumption]** Replays only the changesets after `since`. scn uses this after a landing whose outcome was unknown. |

## Client

| Scion (from the post) | Scion Lite | Notes |
|---|---|---|
| "Scion's negotiation doesn't tell the server what the client already has"; "Starting from a root tree, the client checks its local store for missing trees and requests them recursively, then repeats the process for blobs" | `Scn.fetch`, `tree.TreeWalker` | One level of trees at a time, then blobs; each object is asked for by id. **[Lite assumption]** The local store is the daemon. |
| "Sparse and even virtualized checkouts don't need special support, because the client simply doesn't ask for trees it doesn't want" | `Scn.checkoutSparse`, `Scn.checkoutVirtual`, `Scn.read` | |
| The client "lays out the working copy files on disk, monitors for filesystem changes"; "The working copy is optional" | `Scn.write`, `Scn.delete`, `Scn.status`, `Scn.files` | **[Lite assumption]** The working copy is kept in scn's memory, not on disk, and edits go through scn calls rather than a filesystem watcher. `createChangeset` needs no working copy. |
| An "Antique merge queue" is mentioned but not described; no "global repository lock on the merge queue"; "concurrent changes can conflict" | `Scn.land` | **[Lite assumption]** How landing works is not described, so scn runs a loop: rebase on the server, compare-and-swap the branch, retry if it moved, stop on conflict. |

## Not modelled

- Antique ("our code review tool") and its merge queue.
- Claude fixing conflicts ("Claude can typically fix these conflicts faster than humans can even identify them").
- IAM, and access control per repository and per directory.
- "Each daemon starts from a periodically refreshed snapshot".
- Caches that "stream objects directly from memory, in the disk format that the daemon will consume".
- Derived data other than root trees: "dedicated structures for file blame and path history and skip lists for the commit graph", and indices "for performing copy detection or path-based file search".
- Spanner's and cloud storage's internal replication.
- Creating repositories. The post describes one monorepo, though it mentions "rich access control on repositories".

## Only in Scion Lite, for teaching and tests

- `Spanner.row`, `Spanner.rows` and `Spanner.deleteRowForTest`, plus `CloudStorage.contains`, let tests inspect
  and remove rows.
- Request counters on every tier: `requestsServed`, `hits`, `misses`, `forwardedUpstream`, `objectRequests`.
- `merge.TreeMerge`: a three-way merge of flat snapshots, kept from an earlier version. The server merges path by
  path instead (`ScionServer.resolve`).
