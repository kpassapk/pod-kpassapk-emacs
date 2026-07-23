# Vendored elisp

Third-party libraries the emacs child loads (`-L vendor`), copied in verbatim
and embedded into the pod binary (see `src/elisp.rs`).

| Library | Upstream | Pinned |
|---|---|---|
| `cljbang.el`, `cljbang-core.el`, `cljbang-string.el` | https://github.com/borkdude/cljbang.el | `f18da3e525a2b9975b5fd0d476822375448789c9` (MIT) |
| `parseedn.el` | https://github.com/clojure-emacs/parseedn | — |
| `parseclj*.el`, `a.el` | https://github.com/clojure-emacs/parseclj | — |
| `bencode.el` | https://github.com/skeeto/emacs-bencode | — |

To bump one: copy the new files over, record the rev here, run `bb test`.
`cljbang-mode.el` is intentionally not vendored — it is interactive-only.
