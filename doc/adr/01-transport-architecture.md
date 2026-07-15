# ADR 0001 — Transport architecture: bb shim + Emacs brain over base64 lines

Status: Accepted (2026-06-21). Amended (2026-07-13): the shim is now a Rust
binary (`src/main.rs`) rather than a babashka script — same transport, same
division of labor; the elisp sources are embedded in the binary so releases
are a single file. See ADR-0002 (proposed) for a socket transport that would
replace the base64-line framing decided here.

## Context

A babashka pod is a subprocess that speaks the pod protocol over stdio with
**bencode** framing; payloads here are **EDN**. The references suggested an
elegant "Emacs is the pod" design: `emacs --batch` reading/writing bencode
directly via the elisp `bencode` library and encoding payloads via `parseedn`.

We empirically tested whether `emacs --batch` can read the raw bencode stream
babashka produces.

### Experiments (emacs 31.0.50, macOS arm64)

| Primitive | Result |
|-----------|--------|
| `read-char` loop | **Hangs** — reads the terminal, not piped stdin. Unusable. |
| `read-string` (binary mode) | Reads piped stdin **incrementally** and **byte-faithfully** (0xFF, multibyte preserved) — but **line-delimited**: blocks until a `\n`. |
| `insert-file-contents-literally "/dev/stdin"` | Reads raw bytes but **only to EOF** — cannot drive a request/response loop. |

We also captured the bytes babashka sends a pod on startup:

```
d2:id36:11ed75be-7139-477f-a699-f0c3ee073f522:op8:describee
```

Pure bencode, **no newlines, no trailing newline**. A line reader deadlocks on
this stream. `emacs --batch` has no "read exactly N raw bytes" primitive.

## Decision

Insert a thin **babashka shim** as the pod executable. It owns the
babashka-facing stdio and is a bencode-unaware byte transcoder that converts the
raw bencode stream to/from **base64 lines** for an `emacs --batch` child:

- babashka→emacs: read raw bytes, base64-encode, write one line.
- emacs→babashka: read base64 line, decode, write raw bytes.

base64 is ASCII and newline-free, so it is safe for Emacs' line-oriented
`read-string`, and lossless for arbitrary bencode bytes. The Emacs child holds
the protocol logic and uses **both** `bencode` (wire) and `parseedn` (EDN
payloads), and the child stays warm across invokes.

## Alternatives considered

- **Emacs-is-the-pod (pure elisp, no shim).** Rejected: impossible — Emacs
  `--batch` cannot read babashka's newline-free raw bencode stream.
- **bb owns bencode + drives an Emacs daemon via `emacsclient --eval`.**
  Rejected as primary: fragile shell-escaping of arbitrary elisp and large EDN
  results, daemon socket lifecycle, and it requires a bencode codec in Clojure
  while not using the elisp `bencode` library. The shim approach keeps bb dumb
  and uses both referenced elisp libraries.

## Consequences

- One extra in-process hop (base64 transcode); negligible latency.
- The shim never parses bencode — it cannot corrupt the protocol; correctness
  lives in one place (the elisp brain).
- Emacs binary IO quirks are sidestepped entirely: only ASCII base64 lines cross
  the child's stdio; binary lives only inside Emacs buffers.
