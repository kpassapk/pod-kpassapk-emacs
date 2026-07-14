# Packages

## `pod.kpassapk.emacs`

| Var         | Args               | Returns                              | Notes |
|-------------|--------------------|--------------------------------------|-------|
| `eval`      | `[code]`           | the value of the last form, as EDN   | `code` is a string of one or more top-level elisp forms; they are read and evaluated as a `progn`. |
| `eval-file` | `[path]`           | the file's base name (string)        | `load`s an `.el` file into the (warm) Emacs process. Useful for defining helpers you then call via `funcall`. |
| `funcall`   | `[fn & args]`      | the function's return value, as EDN  | Calls the named elisp function `fn` (string or symbol) with `args`. The args are marshalled from EDN to elisp values, so you pass *data*, not string-spliced code — no `(str "(" ... ")")`. Pairs with `eval-file`: load helpers, then call them with real arguments. |
| `version`   | `[]`               | map                                  | `{:emacs-version "31.0.50" :major-version 31 :exec "/path/to/emacs"}`. |

```clojure
(emacs/eval "(mapcar #'1+ '(1 2 3))")   ;=> (2 3 4)
(emacs/version)
;;=> {:emacs-version "31.0.50", :major-version 31, :exec "/Applications/Emacs.app/Contents/MacOS/Emacs"}

;; funcall passes data, not code — no string-building:
(emacs/funcall "upcase" "hi")            ;=> "HI"
(emacs/funcall "format" "%s-%d" "x" 7)   ;=> "x-7"
;; load your own elisp, then call it with real arguments:
(emacs/eval-file "my-helpers.el")
(emacs/funcall "my-report" {:env "prod"} [1 2 3])
```

**elisp → EDN value mapping** (via `parseedn`):

| elisp                         | EDN       |
|-------------------------------|-----------|
| hash-table                    | map       |
| plist / list                  | list      |
| vector                        | vector    |
| keyword                       | keyword   |
| `t`                           | `true`    |
| `nil`                         | `nil`     |
| non-serializable (buffer, fn) | string repr |

## `pod.kpassapk.emacs.org`

Each reads or runs an org file through real org-mode in a throwaway buffer.

| Var         | Args              | Returns                                          | Notes |
|-------------|-------------------|--------------------------------------------------|-------|
| `outline`   | `[path]` / `[path opts]` | `{:file :title :children [node ...]}`     | Nested headline tree. `:title` is the `#+TITLE:` keyword (may be `nil`). |
| `headlines` | `[path]` / `[path opts]` | vector of nodes (flat, document order)    | Same nodes as `outline` but un-nested (no `:children`). |
| `to-edn`    | `[path]` / `[path opts]` | `{:file :title :children [node ...]}`     | Like `outline`, but every node also gets a `:body` (the entry's text, excluding subheadings). |
| `src-blocks`| `[path]` / `[path opts]` | vector of block nodes (document order)    | Lists every src block so a driver can discover and address them. Each node: `:index` (0-based), `:name` (or `nil`), `:lang`, `:begin`, `:header-args` (map, or `nil`), `:body`. |
| `execute`   | `[path]` / `[path opts]` | the block's result, as EDN                | Runs one org-babel src block and returns its value. Pick the block with `opts` (`:name` / `:index` from `src-blocks`); see below. The block's language backend autoloads (`sh`/`bash`/… → `ob-shell`, else `ob-LANG`). `org-confirm-babel-evaluate` is bound to `nil`, so blocks run without prompting. |

`opts` is an EDN map. Recognized keys:

| Opt          | Type   | Used by      | Effect |
|--------------|--------|--------------|--------|
| `:max-level` | int    | the readers  | Only include headlines at or above this depth (level ≤ N). |
| `:name`      | string | `execute`    | Run the block whose `#+name:` matches. |
| `:index`     | int    | `execute`    | Run the Nth src block (0-based, document order). |

`execute` with no `:name`/`:index` runs the file's sole src block, or errors if
there are several. Out-of-range `:index` and unknown `:name` both error.

```clojure
(org/execute "examples/sample.org" {:index 0})  ;=> "hello"   ; the echo block
(org/execute "tasks.org" {:name "deploy"})       ; run the block named "deploy"
```

```clojure
(org/headlines "examples/sample.org" {:max-level 1})
;;=> [{:level 1 :title "Planning" :begin 42}
;;    {:level 1 :title "Implementation" :begin 388}
;;    {:level 1 :title "Done" :begin 508}]
```

**Node shape.** A headline node is a map. Only `:level`, `:title`, and `:begin`
are always present; the rest appear only when the headline has them:

| Key           | Type             | Always? | Meaning |
|---------------|------------------|---------|---------|
| `:level`      | int              | yes     | Outline depth (1 = top). |
| `:title`      | string           | yes     | Headline text (no TODO/priority/tags). |
| `:begin`      | int              | yes     | Buffer position of the headline. |
| `:todo`       | string           | no      | TODO keyword, e.g. `"TODO"` / `"DONE"`. |
| `:priority`   | string           | no      | Priority cookie, e.g. `"A"`. |
| `:tags`       | vector of string | no      | Headline tags. |
| `:properties` | map              | no      | Drawer properties, keyword-keyed (skips org's built-ins like `CATEGORY`/`PRIORITY`/`TODO`/`TAGS`). |
| `:scheduled`  | string           | no      | `SCHEDULED:` timestamp, raw. |
| `:deadline`   | string           | no      | `DEADLINE:` timestamp, raw. |
| `:children`   | vector of node   | no      | Sub-headlines (omitted on leaves). |
| `:body`       | string           | no      | Entry text. Only from `to-edn`. |

## `pod.kpassapk.emacs.calc`

Hand arbitrary-precision arithmetic, exact fractions, units, and symbolic
algebra to Emacs' built-in **Calc** and get the formatted result back as a
string.

| Var       | Args           | Returns       | Notes |
|-----------|----------------|---------------|-------|
| `eval`    | `[expr]`       | result string | `expr` is a Calc algebraic expression (e.g. `"2^100"`, `"sqrt(2)"`, `"evalv(pi)"`). Big integers, exact fractions, matrices and symbolic algebra all work. |
| `convert` | `[expr units]` | result string | Re-express `expr` (which carries its own units) in the target `units`. |

Precision is Calc's default; there is no per-call precision knob — Calc caches
precision in process-global state and the Emacs child is long-lived, so a change
would leak into unrelated later calls. Pass an explicit form like `"evalv(pi)"`
for numeric output. A malformed expression throws with Calc's parser message.

```clojure
(calc/eval "2^100")           ;=> "1267650600228229401496703205376"
(calc/eval "sqrt(2)")         ;=> "1.41421356237"
(calc/convert "2 in" "cm")    ;=> "5.08 cm"
(calc/convert "55 mph" "kph") ;=> "88.51392 kph"
```

## `pod.kpassapk.emacs.project`

Point Emacs' built-in **`project`** library at a path and read the enclosing
project's root and tracked files, using the same VC-aware detection Emacs uses
interactively.

| Var     | Args                     | Returns           | Notes |
|---------|--------------------------|-------------------|-------|
| `root`  | `[path]`                 | root dir (string) | Absolute path of the project enclosing `path`. Throws if `path` is in no project. |
| `files` | `[path]` / `[path opts]` | vector of paths   | The project's tracked files. With `{:relative true}` the paths are relative to the root; otherwise absolute. |

`path` may be any file or directory inside the project.

```clojure
(project/root ".")                   ;=> "/home/me/src/myproj/"
(project/files "." {:relative true}) ;=> ["README.md" "src/core.clj" ...]
```

## `pod.kpassapk.emacs.org-roam`

Read an [org-roam](https://www.orgroam.com/) knowledge graph as data: the nodes
in a roam directory and the backlinks pointing at any one of them. org-roam is
an external package, so the pod installs it on first `require` (via
`use-package` with `:ensure`).

| Var         | Args            | Returns             | Notes |
|-------------|-----------------|---------------------|-------|
| `nodes`     | `[]` / `[opts]` | vector of node maps | Every node in the roam directory. Each: `:id`, `:title`, `:file`, `:level`, `:tags`. |
| `backlinks` | `[opts]`        | vector of link maps | Backlinks to the node `:id`. Each: `:source-id`, `:source-title`, `:point`. |

`opts` is an EDN map. Recognized keys:

| Opt          | Type   | Used by     | Effect |
|--------------|--------|-------------|--------|
| `:directory` | string | both        | The org-roam directory to read (defaults to the configured `org-roam-directory`). |
| `:id`        | string | `backlinks` | The node whose backlinks to return (required). |

The org-roam SQLite db is synced on each call, so results reflect the
directory's current contents.

```clojure
(org-roam/nodes {:directory "~/roam"})
;;=> [{:id "a1b2…" :title "Zettelkasten" :file "~/roam/zk.org" :level 0 :tags []}
;;    ...]
(org-roam/backlinks {:directory "~/roam" :id "a1b2…"})
;;=> [{:source-id "c3d4…" :source-title "Note-taking" :point 312}]
```

## `pod.kpassapk.emacs.devops`

Manage infrastrucutre with [devops.el](https://github.com/kpassapk/devops.el). The package is installed on first `require` via `use-package` with `:vc`.

| Var       | Args     | Returns               | Notes |
|-----------|----------|-----------------------|-------|
| `tangle`  | `[opts]` | vector of result maps | Tangle a heading's src blocks to its devops target(s). Each result: `:tag` (the heading's target tag), `:target` (the resolved TRAMP path), `:files` (count of files written). |

`opts` is an EDN map:

| Opt          | Type   | Used by   | Effect |
|--------------|--------|-----------|--------|
| `:file`      | string | both      | Path to the org file (**required**). |
| `:heading`   | string | `tangle`  | Tangle the heading with this exact title. |
| `:custom-id` | string | `tangle`  | Tangle the heading with this `CUSTOM_ID` (stable across title edits). |
| `:all`       | bool   | `tangle`  | Tangle every target-tagged heading in the file instead. |
| `:name`      | string | `execute` | Run the block whose `#+name:` matches. |
| `:index`     | int    | `execute` | Run the Nth src block (0-based, document order). |

`tangle` needs exactly one of `:heading`, `:custom-id`, or `:all`. A heading's
target tag resolves to a `:dir` that devops injects into the block's babel
params, so the block tangles/runs on its target. `org-confirm-babel-evaluate` is
bound to `nil`.

```clojure
(devops/tangle {:file "infra.org" :all true})
;;=> [{:tag "web01" :target "/ssh:web01:/etc/nginx/" :files 2} ...]
(devops/execute {:file "infra.org" :name "deploy"})
```

