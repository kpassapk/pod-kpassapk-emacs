# pod-kpassapk-emacs — Design

## Goal

A [babashka pod](https://github.com/babashka/pods) that exposes Emacs to babashka
scripts. You can:

- Evaluate arbitrary Emacs Lisp from babashka and get the result back as EDN.
- Split a program between babashka (the driver) and Emacs Lisp (the org-mode /
  text-processing engine). The flagship use case: a babashka driver reads an
  org-mode file *through Emacs* and gets a structured outline back as EDN.
- Use the pod even on a machine without Emacs installed — the pod resolves an
  Emacs binary for the host architecture, downloading a portable build if needed.

## The transport problem (why there is a shim)

A babashka pod is a subprocess that speaks the pod protocol over **stdio** using
**bencode** framing. The obvious design is "Emacs *is* the pod": run
`emacs --batch` and have Emacs read bencode from stdin and write bencode to
stdout, using the elisp `bencode` and `parseedn` libraries.

That does not work, and we proved it empirically (see
`docs/adr/0001-transport-architecture.md`). In `emacs --batch`:

- `read-char` / `read-event` read the *terminal*, not piped stdin — they hang.
- `read-string` reads piped stdin fine, **incrementally**, and with
  `(set-binary-mode 'stdin t)` it preserves raw bytes exactly — **but it is
  line-delimited**: it blocks until it sees a newline (byte `0x0A`).
- `insert-file-contents-literally "/dev/stdin"` reads raw bytes but only to EOF,
  so it can't drive a request/response loop.

babashka writes bencode with **no newlines** (verified: the describe message on
the wire is `d2:id36:...22:op8:describee`). So a line reader deadlocks on the
real stream. Emacs `--batch` has no "read exactly N raw bytes" primitive.

## Architecture: thin shim + Emacs brain

```
            raw bencode (stdio)              base64 lines (stdio)
  babashka <--------------------> Rust shim <--------------------> emacs --batch
                                  (dumb pipe)                      (the brain)
```

- **Rust shim** (`src/main.rs`, built to the `pod-kpassapk-emacs` pod
  executable; originally a babashka script). Owns the babashka-facing stdio.
  It is a *dumb, bencode-unaware* byte transcoder:
  - babashka→emacs: read available raw bytes, base64-encode the chunk, write it
    as one newline-terminated line to the Emacs child's stdin.
  - emacs→babashka: read a base64 line from the Emacs child's stdout,
    base64-decode it, write the raw bytes to babashka.
  base64 is pure ASCII and newline-free, so it is safe to feed Emacs'
  line-oriented `read-string`, and it carries arbitrary binary bencode losslessly.
  The shim also resolves the Emacs binary, materializes the embedded elisp
  sources (a repo checkout enclosing the binary is used directly instead), and
  manages the child's lifecycle.

- **Emacs `--batch` child** (`resources/pod-emacs.el`). The actual pod logic:
  1. Read a base64 line (`read-string`), decode to raw bytes, append to an
     internal unibyte buffer.
  2. Pull complete messages with `bencode-decode-from-buffer` (handles partial
     messages spanning chunks — "needs more input" just loops back to step 1).
  3. Dispatch `describe` / `invoke` / `shutdown`.
  4. For `invoke`, parse the EDN `args` with `parseedn-read-str`, run the var,
     format the result with `parseedn-print-str`.
  5. `bencode-encode` the reply, base64-encode it, `princ` it as one line.

  Only base64 lines ever touch the child's stdout; warnings/messages go to
  stderr, so the protocol stream stays clean.

This uses **both** referenced elisp libraries (`bencode`, `parseedn`) and keeps
the protocol brain in elisp, where org-mode lives.

## Pod surface (namespaces / vars)

- `pod.babashka.emacs/eval` — eval an elisp string, return result as EDN.
- `pod.babashka.emacs/eval-file` — load an `.el` file in the Emacs process.
- `pod.babashka.emacs/version` — Emacs version info.
- `pod.babashka.emacs.org/outline` — read an org file, return its outline as EDN
  (nested headlines: `:level :title :todo :tags :properties :children`).

`format` is `edn`. elisp→EDN mapping (via parseedn, with a thin wrapper for
predictability): plist/hash-table → map, vector → vector, list → list, keyword →
keyword, t→true, nil→nil, non-serializable (buffers, functions) → string repr.

## Bundling Emacs ("works without Emacs installed")

Resolution order in the shim:
1. `POD_BABASHKA_EMACS_BIN` env override.
2. Cached download under `$XDG_CACHE_HOME/pod-kpassapk-emacs/`.
3. System `emacs` on `PATH`.
4. Download a portable Emacs for the host os/arch into the cache, then use it.

Per-os/arch download sources are kept in a table in the shim and are overridable.
macOS arm64 uses an Emacs.app build (binary at `Contents/MacOS/Emacs`, ships its
own lisp); Linux uses an AppImage/portable tarball.
