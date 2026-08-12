# ADR 02 — Socket transport

Status: Proposed (2026-07-15). Revised (2026-08-11): adds the reverse-nREPL
framing, and splits the decision into a jack-in mode (as originally proposed)
and an attach mode (talk to a running Emacs).

## Context

ADR-0001 chose base64-line framing over stdio because `emacs --batch` cannot
read babashka's newline-free raw bencode stream from a pipe: `read-string` is
line-delimited, `read-char` reads the terminal, and there is no "read exactly N
raw bytes" primitive. The Rust shim exists to transcode raw bencode to/from
base64 lines.

That framing makes the child's stdout a protocol channel, which is fragile in a
pod whose core op is evaluating arbitrary user elisp. Issue #1 was exactly
this: user code writing to `standard-output` corrupted the base64 stream and
killed the session. The fix (rebind `standard-output` to
`external-debugging-output` for the whole loop) closes the common case but is
not airtight — `send-string-to-terminal`, code that rebinds `standard-output`
back, or a subprocess inheriting fd 1 can still corrupt the stream.

Babashka's pod client supports socket transport. It is not in
the pods README; the contract below is read from
`babashka/pods` `src/babashka/pods/impl.clj` (`run-pod`, `port-file`,
`read-port`):

1. The client opts in per load: `(pods/load-pod "./pod-kpassapk-emacs"
   {:transport :socket})`. Pod-registry manifests can carry
   `:options {:transport :socket}`, which the resolver merges into the load
   opts, so registry users get it without writing the option.
2. babashka launches the pod with env `BABASHKA_POD_TRANSPORT=socket` and
   inherits the pod's stdio (stdout/stderr go to the terminal).
3. The pod picks a free port, listens on localhost, and writes `<port>\n` to
   the file `.babashka-pod-<PID>.port` in the client's working directory,
   where PID is the pod process's pid — i.e. the shim's pid, since the shim is
   the pod executable.
4. The client polls that file until it exists and ends in `\n`, parses the
   port, connects to `localhost:<port>`, and speaks raw bencode over the
   socket. The file is deleted on client exit.

Unlike batch stdio, Emacs is good at sockets: `make-network-process` with
`:server t` works in `--batch`, and process filters deliver raw binary
chunks with no line-delimiting. The primitive gap that forced base64 framing
does not exist on this path.

### Prior art: this is nREPL with the roles swapped

The README calls the goal a "reverse nrepl". The analogy is load-bearing
rather than decorative — babashka's pod protocol is nREPL-shaped, and the only
thing that reverses is which end listens.

|              | nREPL / CIDER                      | Pod socket transport                         |
|--------------|------------------------------------|----------------------------------------------|
| Server       | Clojure process                    | Emacs                                        |
| Client       | Emacs (`nrepl-client.el`)          | babashka                                     |
| Wire         | bencode over TCP                   | bencode over TCP                             |
| Correlation  | `id` per message                   | `id` per message                             |
| Terminator   | `status` `["done"]`                | `status` `["done"]`                          |
| Handshake    | `describe`                         | `describe`                                   |
| Work op      | `eval`                             | `invoke`                                     |
| Teardown     | `close`                            | `shutdown`                                   |
| Discovery    | server writes `.nrepl-port`        | pod writes `.babashka-pod-<PID>.port`        |

Two things follow.

**It is an existence proof.** `nrepl-client.el` has spoken raw bencode over
`make-network-process` from elisp for years. Neither the codec nor the
transport is novel on the Emacs side. ADR-0001's negative result was about
`--batch` *stdio*, not about Emacs.

**It names the mode we have not built.** CIDER distinguishes `cider-jack-in`
(spawn a process, then connect to it) from `cider-connect` (attach to a process
that is already running). Everything ADR-0002 originally proposed is jack-in:
babashka launches the pod, the pod is a fresh `emacs --batch`, and it dies with
the session — no user config, no buffers, no live state. The interesting case
is connect: pointing bb at the Emacs the user is already sitting in. Socket
transport is what makes that reachable, because the protocol no longer needs a
pipe to a child process it owns.

**Where the analogy stops.** The JVM is multi-threaded, so nREPL gives each
client a `clone`d session with its own dynamic bindings and its own eval
thread, and can `interrupt` one without disturbing the others. Emacs is
single-threaded. There is no session isolation to be had, no interrupt, and an
eval blocks every other client — including the human typing in that Emacs —
until it returns.

## Decision

Add socket transport as a **second, additive transport**, selected by the
`BABASHKA_POD_TRANSPORT` env var that babashka already sets. Stdio/base64
remains the default and is unchanged; nothing breaks for existing consumers.

Transport selection lives in one place in each component: the shim checks the
env var to decide pump-vs-portfile; `pod-emacs-main` checks it to decide
`read-string` loop vs network server. Everything from bencode decode inward is
shared.

### Mode A — jack-in (spawned `emacs --batch`)

The default when `BABASHKA_POD_TRANSPORT=socket`.

- **Emacs child** owns the protocol socket:

  ```elisp
  (setq pod-emacs--server
        (make-network-process
         :name "pod-emacs" :server t :host 'local :service t ; t = free port
         :coding 'binary
         :filter #'pod-emacs--socket-filter
         :sentinel #'pod-emacs--socket-sentinel))
  (process-contact pod-emacs--server :service) ; => the chosen port
  ```

  The filter appends raw bytes to the same unibyte buffer ADR-0001 uses; the
  existing `bencode-decode-from-buffer` message loop is reused.
  Replies go out with `process-send-string` (raw bencode). The batch main loop becomes
  `(while pod-emacs--running (accept-process-output nil 1))`.

- **Rust shim** stops transcoding and becomes a pure launcher/supervisor:
  resolve Emacs, materialize elisp, spawn the child, then

  1. read one line from the child's stdout (the port number),
  2. write `<port>\n` to `.babashka-pod-<shim-pid>.port` in the CWD
     (the shim knows its own pid; the child does not),
  3. wait on the child.

- **Lifecycle**: the socket sentinel exits Emacs when the client connection
  closes. Without it, a killed client orphans the Emacs process. The shim keeps
  its existing behavior of exiting with the child's status.

### Mode B — attach (running Emacs)

Recorded here as the intended second step, **not part of the initial
implementation**: it is the same wire contract as Mode A with a different way
of obtaining the Emacs process, so it belongs in this ADR rather than its own.

Requested by the user's environment (e.g. `POD_EMACS_SOCKET=<server-name>`),
which babashka passes through to the pod process. Attach implies
`:transport :socket` — it cannot work on the stdio path, because there is no
pipe to a daemon we did not spawn.

- **Shim** does not spawn Emacs. It bootstraps over `emacsclient`:

  ```
  emacsclient -s "$POD_EMACS_SOCKET" --eval \
    '(progn (load "<materialized>/pod-emacs.el") (pod-emacs-serve))'
  ```

  which prints the chosen port on stdout. The shim then writes the port file
  exactly as in Mode A. `emacsclient` is used **once**, for a fixed one-line
  form; no user elisp and no EDN payload ever crosses argv.

- **Emacs** runs the same `make-network-process` server as Mode A. No batch
  loop is needed — the daemon's own event loop already drives the filter.

- **Lifecycle inverts.** The sentinel must `delete-process` the server and
  nothing else; killing Emacs on client disconnect would kill the user's
  editor. The `standard-output` → `external-debugging-output` rebind from
  ADR-0001 must not be applied in a daemon: it is unnecessary off fd 1 and it
  hijacks the user's own output.

Open questions to settle before implementing:

- **Shim termination.** In Mode A the shim exits with its child; in Mode B it
  has no child, and it cannot observe the protocol socket to know the client
  left. Leading candidate is a second control connection the shim holds open
  and Emacs closes from the sentinel, giving the shim an EOF to exit on.
- **Per-connection state.** `pod-emacs--namespaces` and
  `pod-emacs--client-vars` are process globals — correct for a one-shot batch
  child, shared by every bb client attached to one daemon. Mode B is
  single-client until these are keyed by connection, and `pod-emacs-serve`
  should refuse or reuse rather than open a second listener.

## Alternatives considered

- **Socket-only (drop stdio).** Rejected: every direct-path consumer would
  have to pass `{:transport :socket}`, and forgetting it hangs — the pod waits
  for a connection while the client writes `describe` to stdin that nothing
  reads. Dual transport costs one branch per component and breaks no one.
- **Shim owns the socket, keeps base64 pipe to Emacs.** Rejected: satisfies
  the wire contract but keeps the framing hack and the stdout-corruption
  surface — all cost, none of the benefit.
- **Harden stdio further instead** (advice on `send-string-to-terminal`,
  etc.). Rejected: whack-a-mole; fd 1 can always be reached by a subprocess.
  Moving the protocol off fd 1 kills the class, not instances.
- **A separate ADR for attach.** Rejected: attach is not a different
  transport, and splitting it would duplicate the entire socket contract in two
  documents that then drift.
- **ADR-0001's "bb drives an Emacs daemon via `emacsclient --eval`".** That
  rejection narrows rather than reverses. Its two reasons — escaping arbitrary
  elisp, and large EDN results through argv — apply to using `emacsclient` as
  *the protocol*. In Mode B it is only the bootstrap; everything after the
  handshake is raw bencode on a socket.

## Consequences

- The stdout-corruption bug class (#1) is structurally impossible on the
  socket path: the protocol never touches fd 1, and babashka inherits the
  pod's stdio, so `princ`/`message` from user elisp just print to the
  terminal.
- Performance is likely to be a bit worse. If unix domain sockets are ever
  supported in babshka pods (see [#64](https://github.com/babashka/pods/issues/64))
  this might not be so bad.
- No base64 hop: no ~33% payload inflation, no line chunking for large EDN
  results.
- Two transports to test. The stdio path stays the default until the socket
  path has soaked; a later ADR may flip the recommendation for registry users
  via the manifest's `:options`.
- The port file lands in the client's CWD, which must be writable; the
  listening port is reachable by any local process for the session's lifetime.
  Both are properties of babashka's socket contract, not choices we can make.

Mode B additionally:

- **Blast radius is the feature and the risk.** User elisp runs in the live
  session against real buffers, real config and real unsaved work. That is the
  entire point of attaching, and it means a pod invoke can leave the editor in
  a modified state.
- **A long eval freezes the user's Emacs.** The pod protocol has no
  `interrupt`, and honoring one would mean cooperative `while-no-input`
  checks that arbitrary user elisp will not perform.
- **Prompts deadlock.** User code that reaches `read-from-minibuffer` or
  enters a recursive edit blocks the pod until a human dismisses it — a
  failure mode that cannot occur in `--batch`.
