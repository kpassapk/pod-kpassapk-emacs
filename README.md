# pod-kpassapk-emacs

![Status](https://img.shields.io/badge/status-alpha-blue)
[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://book.babashka.org#badges)

A [babashka pod](https://github.com/babashka/pods) for emacs.

## But why?

I got the idea for this project when I was trying out [clime](https://github.com/cosmicz/clime) to expose some elisp functions as a command line (CLI) tool. 
It worked, but I kept wanting [lambdaisland/cli][lambdaisland] or [babashka/cli][bb-cli].

It's straightforward for a bb script to communiceate with emacs via `emacsclient`. By why settle for straightforward? If I could treat emacs as a babashka pod, then I could build not only a CLI, but also "chatty" TUIs or other long-running apps that call emacs continuously. Turns out this sort of "reverse nREPL" (from bb to emacs, rather than the other way around) works. 

Around this time, Michiel Borkent released [cljbang.el][cljbang], converting Clojure syntax to elisp. 

[lambdaisland]: https://github.com/lambdaisland/cli
[bb-cli]: https://github.com/babashka/cli
[cljbang]: https://github.com/borkdude/cljbang.el

## What's here

This project bundles in these excellent elisp libraries:

- [emacs-bencode](https://github.com/skeeto/emacs-bencode) — wire framing
- [cljbang.el][cljbang] — powers the `clj!` macro,
  and reads the EDN arguments a call arrives with

It implements the [pod protocol](https://github.com/babashka/pods#the-protocol) to expose Emacs
itself as one Clojure namespace, `pod.kpassapk.emacs`:

- `clj!` writes Clojure and runs it inside Emacs, so any elisp — org-mode, Calc,
  project.el, a package of your own — is callable without a wrapper namespace.
- `install!` takes a `use-package` declaration and installs the package into the
  batch Emacs, so a script reaches third-party elisp without rebuilding the pod.

Earlier versions shipped a table of per-library namespaces
(`pod.kpassapk.emacs.org`, `…org-roam`, …). Those two vars subsume it; see
[Loading elisp](#loading-elisp).

## Quickstart

Load the pod by local path and call it:

```clojure
(require '[babashka.pods :as pods])

(pods/load-pod 'kpassapk/emacs "0.3.1")

(require '[pod.kpassapk.emacs :as emacs])

;; Write Clojure, run it inside Emacs (via cljbang), get EDN back:
(emacs/clj! (+ 1 2))                        ;=> 3
(emacs/clj! (el/upcase "hi"))               ;=> "HI"
(emacs/clj! (->> (el/buffer-list)
                 (mapv el/buffer-name)))    ;=> ["*scratch*" ...]

;; Or evaluate raw Emacs Lisp strings:
(emacs/eval "(+ 1 2)")            ;=> 3
(emacs/eval "(upcase \"hi\")")    ;=> "HI"

;; Pull in an Emacs package, then call it like any other elisp. Here
;; cljbang-org reads an org file as data:
(emacs/install! '(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org")))

(emacs/clj!
 (require '[cljbang.org :as-alias org])
 (vec (take 2 (org/headings "examples/sample.org"))))
;;=>
;; [{:title "Planning" :level 1 :begin 42 :end 388
;;   :todo nil :priority nil :tags #{} :scheduled nil :deadline nil
;;   :properties {:CATEGORY "sample"}
;;   :file "/abs/path/to/examples/sample.org"}
;;  {:title "Define scope" :level 2 :begin 87 :end 323
;;   :todo "TODO" :priority "A" :tags #{"urgent" "planning"}
;;   :scheduled "<2026-06-25 Thu>" :deadline nil
;;   :properties {:CATEGORY "sample" :EFFORT "2h" :CUSTOM_ID "scope"}
;;   :file "/abs/path/to/examples/sample.org"}]
```

See [examples](./examples/) for more.

### The clj! macro

`emacs/clj!` is the main way to talk to Emacs: write Clojure, not stringified
elisp. Its body is captured as forms, sent to the Emacs child, and compiled to
Emacs Lisp there by [cljbang.el](https://github.com/borkdude/cljbang.el) — no
transpiled text, no subprocess on the Emacs side. The last form's value comes
back as EDN.

```clojure
;; el/<name> calls any Emacs Lisp function or variable:
(emacs/clj! (el/find-file "~/notes.org")
            (el/buffer-size))

;; ~x interpolates a babashka value; ~@xs splices a collection:
(let [path "/tmp/notes.org"
      nums [1 2 3]]
  (emacs/clj! (el/find-file ~path))
  (emacs/clj! (+ ~@nums)))          ;=> 6

;; Definitions persist for the pod session: defn helpers once, call later.
(emacs/clj! (defn stale-buffers []
              (->> (el/buffer-list)
                   (filter (fn [b] (let [f (el/buffer-file-name b)]
                                     (and f (not (el/file-exists-p f))))))
                   (mapv el/buffer-name))))
(emacs/clj! (stale-buffers))
```

The body is [cljbang's Clojure dialect](https://github.com/borkdude/cljbang.el):
most of the sequence library, destructuring, threading macros, and `#(...)`
literals work; inside Emacs, maps are hash tables and there are no lazy seqs.
Some arities differ (e.g. `reduce` needs an init value). See the cljbang docs
for the details. Errors thrown in Emacs surface as `ex-info` on the babashka
side, same as `emacs/eval` ([Errors](#errors)).

For raw source strings there is also `(emacs/eval-clj "(reduce + 0 [1 2 3])")`,
and `emacs/eval` still evaluates plain Emacs Lisp.

### Loading elisp

The pod carries no library of its own. `emacs/install!` takes a
[use-package](https://www.gnu.org/software/emacs/manual/html_mono/use-package.html)
declaration and runs it in the batch Emacs, so anything use-package can install
is one call away — a built-in that only needs loading, a package from an ELPA
archive, or one from git:

```clojure
(emacs/install! 'calc)                     ; built-in: just load it
(emacs/install! '(org-roam :ensure t))     ; from an ELPA archive
(emacs/install! '(cljbang-org
                  :vc (:url "https://github.com/kpassapk/cljbang-org")))
```

The head of the declaration is the package symbol and the rest are
use-package's own keywords, so `:config`, `:after` and friends work as usual.
The call is synchronous and idempotent: it returns with the package installed,
or throws. After that the package is ordinary elisp — call it with `clj!`.

This replaces the deferred namespaces earlier versions shipped
(`pod.kpassapk.emacs.org-roam` and the like), where requiring a namespace
installed its package and gave you a handful of pod-side wrapper vars. `clj!`
made the wrappers unnecessary and `install!` covers the installing, so adding a
library no longer means forking and rebuilding the pod.

## Requirements

- **Clojure / babashka** — to run your scripts and load the pod.
- **Emacs** — the pod tries a few strategies to resolve the emacs binary. See [Emacs resolution](#emacs-resolution).

The pod executable itself is a self-contained binary. Grab a platform build from the
[releases page](https://github.com/kpassapk/pod-kpassapk-emacs/releases), or
build from source with [Rust](https://rustup.rs):

```
cargo build --release   # -> target/release/pod-kpassapk-emacs
```

See the [ADRs](doc/adr) for more on what the pod executable does.

See [examples](examples/README.md).

## Packages

See [doc/packages.md](doc/packages.md) for the vars the pod exposes.

There is nothing to register: an Emacs package becomes usable from Clojure by
installing it with `emacs/install!` and calling it with `emacs/clj!` — see
[Loading elisp](#loading-elisp). No pod fork, no rebuild.

If a package needs elisp glue to be pleasant from Clojure, write the glue as an
ordinary Emacs package and install that. [cljbang-org](https://github.com/kpassapk/cljbang-org)
does exactly this for org-mode — it returns plain maps and leaves the shaping to
the caller — and both org examples here use it.

## Errors

An elisp error becomes a thrown `ex-info` on the babashka side. The Emacs error
message is the `ex-message`; `ex-data` carries the error symbol and the var:

```clojure
(try
  (emacs/eval "(error \"boom\")")
  (catch clojure.lang.ExceptionInfo e
    (ex-message e)  ;=> "boom"
    (ex-data e)))   ;=> {:type "error", :var "pod.kpassapk.emacs/eval"}
```

## Emacs resolution

When the pod starts, the shim resolves an Emacs binary in this order:

1. **`$POD_KPASSAPK_EMACS_BIN`** — explicit override; used as-is.
2. **System `emacs`** on `PATH` (on macOS it also checks
   `/Applications/Emacs.app/Contents/MacOS/Emacs`; on Linux, well-known
   locations like `/usr/bin/emacs` and `/snap/bin/emacs`).

Customize via these environment variables:

| Env var                    | Purpose                                                              |
|----------------------------|----------------------------------------------------------------------|
| `POD_KPASSAPK_EMACS_BIN`   | Force a specific Emacs executable (skips all other resolution).      |
| `POD_KPASSAPK_EMACS_CACHE` | Cache directory for extracted elisp and `emacs.log`.                 |
| `POD_KPASSAPK_EMACS_ELISP` | Load elisp from this directory (expects `resources/` and `vendor/`). |

Unless overriden by `$POD_KPASSAPK_EMACS_CACHE`, the pod sets the cache directory to
`$XDG_CACHE_HOME/pod-kpassapk-emacs` or `~/.cache/pod-kpassapk-emacs`.

The elisp sources are compiled into the binary and extracted to the cache dir
on first run. When the binary sits inside a repo checkout (e.g.
`target/release/`), the checkout's `resources/` and `vendor/` are used
directly, so elisp edits take effect without rebuilding.

## Troubleshooting

The Emacs child's stderr (warnings, errors, messages) is logged to `<cache>/emacs.log` (e.g. `~/.cache/pod-kpassapk-emacs/emacs.log`). 

The pod also prints the resolved emacs path to stderr on startup.

If calls hang, it could be that Emacs that wrote something unexpected to stdout, or a download is in progress. Check `emacs.log`.

Since there is no command loop, undo boundaries are never pushed: edits across `eval` calls merge into a single undo group, so `(undo)` can revert everything at once. Call `(undo-boundary)` after each logical edit (and set `last-command` to `'undo` to continue an undo sequence). See [examples/editor.bb](examples/editor.bb).

## Roadmap

- Socket transport? (see [ADR-02](doc/adr/02-socket-transport.md))

## License

Copyright © 2026 Kyle Passarelli. Distributed under the Eclipse Public License
1.0 — see [LICENSE](LICENSE).

A released binary carries the vendored elisp inside it, so those libraries'
terms travel with it: MIT for cljbang.el, the Unlicense for emacs-bencode, both
reproduced in [NOTICE](NOTICE). A library whose licence does not sit with
EPL-1.0 cannot be vendored here — that is why the EDN printer is our own and
not GPL-licensed parseedn's.
