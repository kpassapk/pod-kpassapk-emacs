# pod-kpassapk-emacs

![Status](https://img.shields.io/badge/status-alpha-blue)
[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://book.babashka.org#badges)

A [babashka pod](https://github.com/babashka/pods) that exposes Emacs to babashka scripts.

## But why?

I got the idea for this project when I was trying out [clime](https://github.com/cosmicz/clime) to expose some elisp functions as a command line (CLI) tool. 
It worked, but I wasn't sure I wanted to learn another CLI framework. What if I could use [lambdaisland/cli][lambdaisland] or [babashka/cli][bb-cli] 
instead?

[lambdaisland]: https://github.com/lambdaisland/cli
[bb-cli]: https://github.com/babashka/cli

## What's here

This project bundles in these excellent elisp libraries:

- [parseedn](https://github.com/clojure-emacs/parseedn)
- [parseclj](https://github.com/clojure-emacs/parseclj)
- [emacs-bencode](https://github.com/skeeto/emacs-bencode)

It implements the [pod protocol](https://github.com/babashka/pods#the-protocol) to expose emacs packages as Clojure namepsaces:

**Built-in:**

- calc
- project.el
- org mode

**Third party:**

- [org roam](https://github.com/org-roam/org-roam)
- [devops.el](https://github.com/kpassapk/devops.el)

### Loading elisp

The pod defers loading of elisp packages until they are required from Clojure. 

```
(require '[pod.kpassapk.emacs.org-roam :as roam]) ;; org-roam downloaded (if necessary) and required here
```

Since loading is deferred, this repository can provide a large library of emacs packages, which users can pick _a la carte_.

See the [Adding Libraries](#adding-libraries) section if you want to add a new elisp library. Pull requests welcome.

## Requirements

- **Clojure / babashka** — to run your scripts and load the pod.
- **Emacs** — the pod tries a few strategies to resolve the emacs binary. See [Emacs resolution](#emacs-resolution).

The pod executable itself is a self-contained Rust binary. Grab a platform build from the
[releases page](https://github.com/kpassapk/pod-kpassapk-emacs/releases), or
build from source with [Rust](https://rustup.rs):

```
cargo build --release   # -> target/release/pod-kpassapk-emacs
```

## Quickstart

Load the pod by local path and call it:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["target/release/pod-kpassapk-emacs"])

(require '[pod.kpassapk.emacs :as emacs]
         '[pod.kpassapk.emacs.org :as org])

;; Evaluate Emacs Lisp, get EDN back:
(emacs/eval "(+ 1 2)")            ;=> 3
(emacs/eval "(upcase \"hi\")")    ;=> "HI"

;; Read an org file through real org-mode:
(org/outline "examples/sample.org")
;;=>
;; {:file "/abs/path/to/examples/sample.org"
;;  :title "Project Roadmap"
;;  :children [{:level 1 :title "Planning" :begin 42
;;              :children [{:level 2 :title "Define scope" :todo "TODO"
;;                          :priority "A" :tags ["urgent" "planning"]
;;                          :scheduled "<2026-06-25 Thu>"
;;                          :properties {:EFFORT "2h" :CUSTOM_ID "scope"}
;;                          :begin 87
;;                          :children [...]}
;;                         ...]}
;;             ...]}
```

See [examples](examples/README.md).

## Packages

See [doc/packages.md](doc/packages.md) for a list of available packages.

To use a built-in emacs package from Clojure, create a pod elisp file in `resources/` which calls `pod-emacs-register`, 
and add the [named feature](https://www.gnu.org/software/emacs/manual/html_node/elisp/Named-Features.html)  to `pod-emacs--deferred` in `pod-emacs.el`.

For example, let's say we have want to be able to require `pod.kpassapk.emacs.foo` from Clojure, with elisp function `foo-func1` 
and Clojure function `(foo/func1)`. We would add a file like this to `resources/pod-emacs-foo.el`:

```
;;; Code:

(defun pod-emacs-func1 ()
  (foo-func1))

... 

(pod-emacs-register
 "pod.kpassapk.emacs.foo"
 `(("func1"    . ,#'pod-emacs-func1)))
 
(provide 'pod-emacs-foo)
;;; pod-emacs-foo.el ends here
```

Then we would add `pod-emacs-foo` (the provided feature name) it to `pod-emacs-deferred`:

```
(defvar pod-emacs--deferred
  ... 
  ("pod.kpassapk.emacs.foo" . pod-emacs-foo) ;; add this
... 
```

If the library is _not_ built into emacs, you can pass in a `use-package` form to `pod-emacs-deferred`. When the library is required (in Clojure), 
the pod will attempt to install it. For example, here we are installing [devops.el](https://github.com/kpassapk/devops.el) from git:

```
("pod.kpassapk.emacs.devops" .
     (pod-emacs-devops . (:use-package devops
				       :ensure t
				       :vc (:url "https://github.com/kpassapk/devops.el"))))
```

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
