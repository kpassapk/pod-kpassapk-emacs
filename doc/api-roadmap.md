# API roadmap

Tracks the planned API improvements after the first cleanup pass. The goal is a
pod surface that is **cleaner** (no hand-written elisp strings in user code) and
**more powerful** (live output, mutation, export).

## Done

- **`org/src-blocks`** — enumerate src blocks as EDN, so a driver discovers and
  addresses blocks without inline elisp.
- **`funcall`** — call a named elisp function with EDN-marshalled data args; pairs
  with `eval-file` to replace string-spliced `eval`.
- **Async in the runbook example** — `future` over the synchronous `execute` var
  instead of hand-rolled `pods/invoke` + handlers.

The rest, in recommended order.

---

## #4 — Rich `execute` result

**Problem.** `execute` returns only the babel value. A runbook usually also wants
the captured output, what kind of result it was, and how long it took.

**Design.** Opt-in via `opts`, default unchanged (back-compat):

```clojure
(org/execute "runbook.org" {:name "build"})                 ;=> "your build is ready!"
(org/execute "runbook.org" {:name "build" :capture :rich})
;;=> {:value "your build is ready!"
;;    :result-type :output            ; from the block's :results header
;;    :lang "sh" :name "build" :begin 119
;;    :elapsed-ms 8013}
```

**Implementation** (`pod-emacs-org-execute` in `resources/pod-emacs-org.el`):

- Bind `start (float-time)` around `org-babel-execute-src-block`.
- Read `:result-type` / `:results` and `lang`/`name` from
  `(org-babel-get-src-block-info t)` (already fetched for `--require-lang`).
- When `(:capture opts)` is `:rich`, return a `pod-emacs--ht` with the fields
  above; otherwise return the bare value as today.

**Scope.** Low. `:value`, `:result-type`, `:lang`, `:name`, `:begin`,
`:elapsed-ms` are cheap. **Separately** capturing raw stdout *and* stderr (apart
from the babel value) is the hard 20% — org-babel already folds output into the
result per `:results`. Defer true split stdout/stderr to #5, where we run the
process ourselves.

**Touches.** `pod-emacs-org.el` (`execute` only — dispatch already forwards
`opts`), README API table, CHANGELOG, one test.

---

## #6 — More org verbs (read-only first)

`src-blocks` is the gateway; the README already promises tangle/agenda. Add the
read-only and write-to-new-file verbs first; defer *mutation of existing files*.

### `org/tangle`  *(do first — read-only, high value)*

```clojure
(org/tangle "config.org")                       ;=> ["/abs/sample.yaml" ...]
(org/tangle "config.org" {:lang "yaml"})        ; only yaml blocks
```

- Wrap `org-babel-tangle-file`; return the vector of written paths.
- `opts`: `:lang` (restrict to one language), `:target-file` (override output).
- Reuse `--require-lang` so backends autoload under `--batch -Q`.

### `org/export`  *(read-only; writes a new artifact or returns a string)*

```clojure
(org/export "doc.org" {:backend :html})         ;=> "<html>…</html>" (string)
(org/export "doc.org" {:backend :md :to-file "doc.md"})  ;=> "/abs/doc.md"
```

- `require` the `ox-*` backend for `:backend` (`ox-html`, `ox-md`, `ox-ascii`).
- Use `org-export-as` (return string) or `org-export-to-file` (write + return
  path) depending on `:to-file`.

### `org/todos`  *(thin read-only convenience over headlines)*

```clojure
(org/todos "tasks.org")                          ; nodes with a :todo, document order
(org/todos "tasks.org" {:state "TODO"})          ; filter by keyword
```

- Filter `pod-emacs-org--collect` results to entries with a `:todo`; optional
  `:state` filter. No new parsing — composes existing node-building.

**Touches per verb.** New fn in `pod-emacs-org.el`, dispatch clause + describe
var in `pod-emacs.el`, README row, CHANGELOG, test.

---

## #5 — Streaming async `execute`

**Problem.** A long block (`sleep 8`) shows a blind spinner — no live output.

**Design.** A new var (don't overload sync `execute`):

```clojure
(org/execute-stream "runbook.org" {:name "build"})
```

declared **`"async" true`** in `describe`. The driver calls it with
`pods/invoke` + `{:handlers {:success … :error …}}` (the original example
pattern — now *justified*, because there are multiple reply messages):

- intermediate messages: same `id`, `status` **without** `"done"`, carrying an
  output chunk (e.g. `{:stream :out :chunk "…"}` in `value`);
- final message: the #4 rich result, `status ["done"]`.

**Challenges.**

1. The brain processes one request at a time in its read loop. To emit chunks
   *while a block runs*, run the shell block via `make-process` with a process
   **filter** that calls `pod-emacs--send` per chunk, then resolves on the
   sentinel. This bypasses `org-babel-execute-src-block`, so we lose babel
   semantics (`:var`, `:session`, non-shell langs). Start shell-only; document
   the limitation.
2. Confirm the exact babashka async wire contract (which keys carry a streamed
   partial value, how the client routes non-final messages to `:success`).
   Prototype against `babashka.pods` before committing the protocol shape.
3. The shim is a dumb byte transcoder and needs **no changes** — multiple
   base64 lines for one `id` already flow through.

**Scope.** Medium-high; the most complex item. Do **after #4** so the final
stream message reuses the rich-result shape.

---

## Later / bigger

- **Mutation verbs** (`org/set-todo`, `org/refile`, `org/set-property`). These
  write back to *existing user files* — the first mutating surface. Needs a
  write-safety decision (atomic temp-file rename? in-place `save-buffer`?
  backup?) worth its own ADR before implementation. Sequence this last.
- **Stateful buffer sessions.** Today every org call re-reads the file into a
  throwaway buffer, so blocks can't share an org `:session` or in-buffer state
  across calls. A handle-based API (`org/open` → ops → `org/close`) would let a
  runbook chain dependent blocks. Larger design; revisit if a concrete use case
  needs cross-block state.

## Suggested sequence

1. **#4** rich `execute` result — cheap; defines the result shape #5 reuses.
2. **#6** `tangle`, then `export`, then `todos` — read-only, independent, fills
   README promises.
3. **#5** `execute-stream` — depends on #4; prototype the async wire first.
4. **#6 mutation verbs** — last; write an ADR on write-safety first.
