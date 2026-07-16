# ADR 02 — Socket transport

Status: Proposed (2026-07-15). 

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

## Decision

Add socket transport as a **second, additive transport**, selected by the
`BABASHKA_POD_TRANSPORT` env var that babashka already sets. Stdio/base64
remains the default and is unchanged; nothing breaks for existing consumers.

When `BABASHKA_POD_TRANSPORT=socket`:

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

Transport selection lives in one place in each component: the shim checks the
env var to decide pump-vs-portfile; `pod-emacs-main` checks it to decide
`read-string` loop vs network server. Everything from bencode decode inward is
shared.

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
