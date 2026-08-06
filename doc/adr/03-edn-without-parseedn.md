# ADR 0003 — EDN without parseedn: keep the vendored elisp EPL-compatible

Status: Accepted (2026-08-06).

## Context

The pod is EPL-1.0. It vendors third-party elisp under `vendor/` and embeds
those files verbatim in the released binary (`src/elisp.rs`, `include_str!`),
so the binary published to the babashka pod registry *carries* them.

Three of the vendored libraries were GPL-3.0: `parseedn`, the five `parseclj`
files it parses with, and `a.el`. The FSF treats EPL and GPL as mutually
incompatible, so a single distributed artifact cannot be offered under both.
Nothing forced the issue while the pod was unpublished; publishing to the
registry does.

The runtime story was never the problem. The Rust shim does not link the elisp:
it writes the files out and spawns `emacs -L vendor`, and Emacs is itself GPL,
which is exactly where parseedn is meant to run. The problem is narrower and
harder to argue away — an EPL-1.0 binary with GPL-3.0 source inside it.

The GPL surface turned out to be two function calls: `parseedn-read-str` on the
EDN `args` of an invoke, and `parseedn-print-str` on the reply. `a.el` had no
callers at all.

## Decision

Drop all three GPL libraries. Vendor only licences that sit with EPL-1.0.

- **Reading** goes to `cljbang-edn-read-string` (MIT). cljbang is vendored
  anyway for `clj!` and carries a Clojure reader; `clojure.edn/read-string`
  falls out of it. Maps arrive as hash-tables, the representation parseedn
  gave, so callers of the value did not change.
- **Printing** becomes ours, in `resources/pod-emacs-util.el`. cljbang's
  `pr-str` was the obvious substitute and is wrong here in three ways: it
  leaves `\t`, `\n` and `\r` raw inside strings, it has no notion of a value it
  cannot print (a buffer comes out as a bare `*scratch*`, which is not EDN),
  and it does not read an alist or a plist as a map. Reproducing the mapping
  around a printer that disagrees with it is more code than a printer that
  agrees, so `pod-emacs--edn` is a printer.

The value mapping is the pod's wire contract, and it did not change: a
differential test over 45 values — control characters, alists, plists, dotted
pairs, nested collections, sets, buffers, records — produced byte-identical EDN
before and after.

`scripts/vendor.clj` now records each library's licence and copyright and
generates `NOTICE` alongside `vendor/README.md`, so the attribution MIT asks
for ships with the binary that needs it.

## Consequences

The registry can be handed an EPL-1.0 binary containing only MIT and Unlicense
code. `vendor/` loses about 60KB and a whole parser stack.

cljbang now loads at startup rather than on the first `clj!`, since every
invoke with arguments needs its reader. Measured at ~10ms, inside the noise of
Emacs startup.

The argument reader is narrower than parseedn's on syntax neither is likely to
see. `#uuid` used to decode to `(edn-uuid "...")` and now raises; Clojure
ratios and character literals — `1/2`, `\a` — arrive as elisp symbols rather
than as `0.5` and `97`. `#inst` and unknown tags raised under parseedn too.
This is cljbang's reader, so `clj!` bodies already had these limits; the cost
of the change is that arguments now share them. Revisit if a caller hits it.

Anything vendored from here on has to clear the licence bar first — see the
note above `libs` in `scripts/vendor.clj`.
