# API

## `pod.kpassapk.emacs`

| Var         | Args               | Returns                              | Notes |
|-------------|--------------------|--------------------------------------|-------|
| `clj!`      | `[& body]`         | the value of the last form, as EDN   | Macro (client-side). Writes Clojure, runs it inside Emacs via cljbang. `~x`/`~@xs` interpolate babashka values; `el/name` calls elisp. See the [README](../README.md#the-clj-macro). |
| `eval`      | `[code]`           | the value of the last form, as EDN   | `code` is a string of one or more top-level elisp forms; they are read and evaluated as a `progn`. |
| `eval-clj`  | `[code]`           | the value of the last form, as EDN   | Like `clj!` but takes Clojure source as a string. |
| `eval-file` | `[path]`           | the file's base name (string)        | `load`s an `.el` file into the (warm) Emacs process. Useful for defining helpers you then call via `funcall`. |
| `funcall`   | `[fn & args]`      | the function's return value, as EDN  | Calls the named elisp function `fn` (string or symbol) with `args`. The args are marshalled from EDN to elisp values, so you pass *data*, not string-spliced code — no `(str "(" ... ")")`. Pairs with `eval-file`: load helpers, then call them with real arguments. |
| `use-package!` | `[decl]`        | the package name (string)            | Runs a `use-package` declaration in the batch Emacs — `decl` is that declaration, or a bare symbol for a built-in. Installs when the declaration says to (`:ensure`, `:vc`). See [Declaring packages](#declaring-packages). |
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

**elisp → EDN value mapping**:

| elisp                                    | EDN         |
|------------------------------------------|-------------|
| hash-table                               | map         |
| alist — `(("a" . 1))`                    | map         |
| plist — `(:a 1 :b 2)`                    | map         |
| any other proper list                    | list        |
| vector                                   | vector      |
| keyword                                  | keyword     |
| cljbang set                              | set         |
| `t`                                      | `true`      |
| `nil`                                    | `nil`       |
| non-serializable (buffer, fn, `(1 . 2)`) | string repr |

Emacs can express map-like structures in three ways.
A list stays a list when it is neither shape: every key of an alist has to 
be a cons with an atom for its car, every key of a plist a keyword, 
so `("a" 1)` and `(:a 1 :b)` come back as lists.

A non-serializable value is stringified, so the rest of the
reply is still data — `{:ok 1 :buf (el/current-buffer)}` comes back as a map
with a string under `:buf`, not as one long string.

## use-package!

```clojure
(emacs/use-package! 'calc)                     ;=> "calc"     ; built-in
(emacs/use-package! '(org-roam :ensure t))     ;=> "org-roam" ; from an ELPA archive
(emacs/use-package! '(cljbang-org
                      :vc (:url "https://github.com/kpassapk/cljbang-org")))
;;=> "cljbang-org"

;; keywords are use-package's, so setup travels with the declaration:
(emacs/use-package! '(ob-babashka
                      :ensure t
                      :after org
                      :vc (:url "https://github.com/kpassapk/ob-babashka")
                      :config (add-to-list 'org-babel-load-languages '(babashka . t))))
```

`use-package!` throws if the package is not on the load path afterwards. (Unlike the underlying
emacs macro, which is quiet about a missing package., so `use-package!` checks rather than trusting it. With
`:ensure`, the archive lists are refreshed once if they are empty, so a first
call on a fresh Emacs does not fail with "package is unavailable".

## Calling elisp: use `clj!`

Libraries whose functions take and return plain data need no pod namespace in
front of them — install them if they are third-party, then call them directly
with `emacs/clj!`. The `pod.kpassapk.emacs.calc`, `.project`, `.org`,
`.org-roam`, `.devops` and `.ob-babashka` namespaces were all removed in favor
of this.

```clojure
;; Calc — arbitrary-precision arithmetic and unit conversion:
(emacs/clj! (el/require 'calc) (el/require 'calc-units))
(emacs/clj! (el/calc-eval "2^100"))   ;=> "1267650600228229401496703205376"
(emacs/clj! (el/math-format-value
             (el/math-convert-units (el/math-read-expr "2 in")
                                    (el/math-read-expr "cm"))))  ;=> "5.08 cm"

;; project.el — VC-aware project root and file list:
(emacs/clj! (el/require 'project))
(emacs/clj! (el/expand-file-name (el/project-root (el/project-current nil "."))))
(emacs/clj! (vec (el/project-files (el/project-current nil "."))))

;; org-mode, through cljbang-org: flat heading maps, src blocks, execution:
(emacs/use-package! '(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org")))
(emacs/clj!
 (require '[cljbang.org :as-alias org])
 (mapv :name (org/src-blocks "examples/runbook.org")))
;;=> ["env" "build" "size" "exists" "summary"]
```

`defn` helpers once (definitions persist for the pod session) to add error
handling or shape the result — see [examples/calc-units.bb](../examples/calc-units.bb)
and [examples/org-portal.bb](../examples/org-portal.bb).
