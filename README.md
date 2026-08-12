# pod-kpassapk-emacs

![Status](https://img.shields.io/badge/status-alpha-blue)
[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://book.babashka.org#badges)

A [babashka pod](https://github.com/babashka/pods) for emacs.

## But why?

I got the idea for this project when I was trying out [clime](https://github.com/cosmicz/clime) to expose some elisp functions as a command line (CLI) tool. 
It worked, but I kept wanting [lambdaisland/cli][lambdaisland] or [babashka/cli][bb-cli].

Of course it's straightforward for a bb script to communiceate with emacs: call `emacsclient` with [babashka.Process][bbprocess]. But why settle for straightforward? If I could instead connect as sort of a "reverse nrepl" (from bb to emacs, rather than the other way around) I could build "chatty" TUIs or other long-running babashka apps that call emacs continuously. What could this be useful for? Unclear, but org mode something something. Anyway, moving on.

I just got the connnection part working when Michiel Borkent released [cljbang.el][cljbang]. This simplified things quite a bit, and made the API way nicer. Here is a snippet from the [portal]./examples/org-portal.bb) example.

```clojure
(emacs/clj!
 (require '[cljbang.org :as-alias org])

 (defn outline [file]
   {:file file
	:title (first (:title (org/keywords file)))
	:children (org/tree (org/headings file {:body? true}))}))

(def p (p/open))
(add-tap #'p/submit)
(tap> (emacs/clj! (outline ~org-file)))
```

There's that [org][cljbang-org] thing!

Borkdude said about cljbang, "I'm not sure if any of this is a good idea, but it kinda works for me." I feel kind of the same, especially with a little [library help][cljbang-org] to make some gnarly elisp internals more clojure-y.

[bbprocess]: https://github.com/babashka/process
[cljbang-org]: https://github.com/kpassapk/cljbang-org
[lambdaisland]: https://github.com/lambdaisland/cli
[bb-cli]: https://github.com/babashka/cli
[cljbang]: https://github.com/borkdude/cljbang.el

## What's here

This project bundles in these excellent elisp libraries:

- [emacs-bencode](https://github.com/skeeto/emacs-bencode)
- [cljbang.el][cljbang]

It implements the [pod protocol](https://github.com/babashka/pods#the-protocol) to expose emacs
itself as one Clojure namespace, `pod.kpassapk.emacs`, defining:

- `clj!`, which writes Clojure and runs it inside emacs
- `use-package!`, which (predictably) takes a `use-package` declaration and runs it in emacs

See the [api](./doc/api.md) docs for more.

## Quickstart

Load the pod by local path and call it:

```clojure
(require '[babashka.pods :as pods])

(pods/load-pod 'kpassapk/emacs "0.4.0")

(require '[pod.kpassapk.emacs :as emacs])

(emacs/clj! (+ 1 2))                        ;=> 3
(emacs/clj! (el/upcase "hi"))               ;=> "HI"
(emacs/clj! (->> (el/buffer-list)
                 (mapv el/buffer-name)))    ;=> ["*scratch*" ...]

(emacs/eval "(+ 1 2)")            ;=> 3
(emacs/eval "(upcase \"hi\")")    ;=> "HI"

;; Pull in an Emacs package, then call it like any other elisp.
(emacs/use-package! '(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org")))

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

`emacs/clj!` captures its body as forms, sends them to the Emacs child, and
compiles / converts them to elisp with [cljbang.el](https://github.com/borkdude/cljbang.el)
The last form's value comes back as EDN.

```clojure
;; el/<name> calls any Emacs Lisp function or variable:
(emacs/clj! (el/find-file "~/notes.org")
            (el/buffer-size))

;; ~x interpolates a babashka value; ~@xs splices a collection:
(let [path "/tmp/notes.org"
      nums [1 2 3]]
  (emacs/clj! (el/find-file ~path))
  (emacs/clj! (+ ~@nums)))          ;=> 6

;; Definitions persist for the pod session
(emacs/clj! (defn stale-buffers []
              (->> (el/buffer-list)
                   (filter (fn [b] (let [f (el/buffer-file-name b)]
                                     (and f (not (el/file-exists-p f))))))
                   (mapv el/buffer-name))))
(emacs/clj! (stale-buffers))
```

Errors thrown in Emacs surface as `ex-info` on the babashka side, same as `emacs/eval` ([Errors](#errors)).

For raw source strings there is also `(emacs/eval-clj "(reduce + 0 [1 2 3])")`,
and `emacs/eval` still evaluates plain Emacs Lisp.

### Loading elisp

The pod carries no library of its own. `emacs/use-package!` takes a
[use-package](https://www.gnu.org/software/emacs/manual/html_mono/use-package.html)
declaration and runs it in the batch Emacs, so anything use-package can reach
is one call away — a built-in that only needs loading, a package from an ELPA
archive, or one from git:

```clojure
(emacs/use-package! 'calc)                     ; built-in: just load it
(emacs/use-package! '(org-roam :ensure t))     ; from an ELPA archive
(emacs/use-package! '(cljbang-org
                      :vc (:url "https://github.com/kpassapk/cljbang-org")))
```

The head of the declaration is the package symbol and the rest are
use-package's own keywords, so `:config`, `:after` and friends work as usual. The call returns with the package present, or throws. This is unlike emacs `use-package`, which silently ignores unknown packages. (I guess so that it does not interrupt emacs loading, but it's unfortunate.)

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

See [doc/api.md](doc/packages.md) for the vars the pod exposes.

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
  - Could we connect to an already running emacs instance with socket transport?

## License

Copyright © 2026 Kyle Passarelli. Distributed under the Eclipse Public License
1.0 — see [LICENSE](LICENSE).

A released binary carries the vendored elisp inside it, so those libraries'
terms travel with it: MIT for cljbang.el, the Unlicense for emacs-bencode, both
reproduced in [NOTICE](NOTICE). A library whose licence does not sit with
EPL-1.0 cannot be vendored here — that is why the EDN printer is our own and
not GPL-licensed parseedn's.
