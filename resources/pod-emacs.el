;;; pod-emacs.el --- Emacs Lisp brain for pod-kpassapk-emacs -*- lexical-binding: t; -*-

;; This runs inside `emacs --batch'.  It is the protocol brain of the pod:
;; it speaks the babashka pod protocol (bencode framing, EDN payloads) with a
;; thin babashka shim sitting between it and babashka itself.
;;
;; Transport (see docs/adr/0001-transport-architecture.md): the shim owns the
;; babashka-facing stdio and transcodes the raw bencode byte stream to/from
;; *base64 lines*, because `emacs --batch' can only read newline-delimited
;; stdin (read-string) but not arbitrary raw bytes.  So here:
;;
;;   in:  read-string -> base64-decode -> accumulate bytes -> bencode-decode
;;   out: bencode-encode -> base64-encode -> send-string-to-terminal + "\n"
;;
;; `send-string-to-terminal' is used (not `princ') because batch stdout is
;; block-buffered for pipes; send-string-to-terminal flushes immediately.

;;; Code:

(require 'bencode)
(require 'cljbang)
(require 'cl-lib)
(require 'pod-emacs-util)

(defvar pod-emacs--in-buffer-name " *pod-emacs-in*"
  "Name of the unibyte buffer accumulating raw bencode bytes from stdin.")

;;;; ---------------------------------------------------------------- helpers

(defun pod-emacs--send (ht)
  "Bencode-encode reply HT, base64 it, and write one line to stdout."
  (let* ((enc (bencode-encode ht))             ; unibyte string
         (b64 (base64-encode-string enc t)))    ; t = no line breaks
    ;; send-string-to-terminal flushes; chunk very large payloads to be safe.
    (let ((i 0) (n (length b64)) (chunk 65536))
      (while (< i n)
        (send-string-to-terminal (substring b64 i (min n (+ i chunk))))
        (setq i (+ i chunk))))
    (send-string-to-terminal "\n")))

;;;; ---------------------------------------------------------------- registry

;; Feature modules (pod-emacs-*) register themselves here instead of being
;; hard-wired into describe/dispatch.  Core knows nothing of their internals;
;; the dependency runs one way (modules require core).
;;
;; Third-party elisp is deliberately *not* registered here.  The pod ships no
;; library table: a script installs the package it wants with `install!' (see
;; below) and calls it through `clj!', so adding a library never means forking
;; and rebuilding the pod binary.

(defvar pod-emacs--namespaces nil
  "Registered namespaces, an alist (NS-NAME . VARS).
NS-NAME is the fully-qualified namespace string; VARS is an alist of
\(VAR-NAME . HANDLER), where HANDLER is applied to the invoke args.")

(defun pod-emacs-register (ns vars)
  "Register namespace NS (string) exposing VARS.
VARS is an alist of (VAR-NAME . HANDLER); HANDLER is `apply'd to the
decoded invoke args.  Re-registering NS replaces its previous vars."
  (setf (alist-get ns pod-emacs--namespaces nil nil #'equal) vars))

(defvar pod-emacs--client-vars nil
  "Client-side vars, an alist (NS-NAME . ((VAR-NAME . CODE))).
CODE is Clojure source shipped in the var's `code' field of the describe
reply; the babashka client evaluates it in namespace NS-NAME instead of
creating a remote-invoke stub.  This is how the pod ships macros, which
cannot run remotely: their job is to transform forms on the client.")

(defun pod-emacs-register-client (ns vars)
  "Register client-side VARS, an alist (VAR-NAME . CODE), under NS."
  (setf (alist-get ns pod-emacs--client-vars nil nil #'equal) vars))

(defun pod-emacs--ns-vars (ns handlers)
  "The describe var entries for NS: HANDLERS then client-side vars.
Handler vars are listed by name only (the client makes invoke stubs);
client vars carry their Clojure source in `code'.  Handlers come first so
a shipped macro can reference the stubs it expands to."
  (append
   (mapcar (lambda (v) (pod-emacs--var (car v))) handlers)
   (mapcar (lambda (v) (pod-emacs--var (car v) "code" (cdr v)))
           (alist-get ns pod-emacs--client-vars nil nil #'equal))))

;;;; ---------------------------------------------------------------- describe

(defun pod-emacs--var (name &rest kvs)
  "A describe var entry named NAME, with optional extra string-keyed KVS."
  (apply #'pod-emacs--ht "name" name kvs))

(defun pod-emacs--describe-reply ()
  "Build the describe reply hash-table.
Every registered namespace ships its vars; there is nothing to defer, since
the pod registers no third-party libraries of its own."
  (pod-emacs--ht
   "format" "edn"
   "namespaces" (mapcar (lambda (ns)
                          (pod-emacs--ht
                           "name" (car ns)
                           "vars" (pod-emacs--ns-vars (car ns) (cdr ns))))
                        (reverse pod-emacs--namespaces))
   "ops" (pod-emacs--ht "shutdown" (make-hash-table :test 'equal))))

;;;; ---------------------------------------------------------------- packages

(defun pod-emacs--install (decl)
  "Install and load an Emacs package; return its name as a string.
DECL is a `use-package' declaration whose head is the package symbol, so its
keywords are use-package's: `:ensure t' pulls the package from an archive,
`:vc (:url URL)' from git, and `:after'/`:config' shape the load.  A bare
symbol means \"just load it\", which is all a built-in needs.

This is how a script gets elisp into the batch Emacs — the pod carries no
package registry, so anything installable by use-package is reachable without
rebuilding the pod.  The call is synchronous: it returns with the package
installed, or signals."
  (unless decl (error "install!: missing package declaration"))
  (let* ((decl (if (listp decl) decl (list decl)))
         (name (symbol-name (car decl))))
    (require 'package)
    (package-initialize)
    (require 'use-package)
    ;; A fresh batch Emacs has no archive contents, and `package-install'
    ;; failing there says "package is unavailable", which reads as "no such
    ;; package".  Fetch the lists once, and only when `:ensure' will need them.
    (when (and (memq :ensure decl)
               (null package-archive-contents)
               (null (locate-library name)))
      (package-refresh-contents))
    (eval `(use-package ,@decl) t)
    ;; use-package is quiet when a package is missing and quiet again when
    ;; `:after'/`:defer' postponed the load, so the post-condition to check is
    ;; "installed", not "loaded".
    (unless (locate-library name)
      (error "install!: package %s did not install" name))
    name))

;;;; ---------------------------------------------------------------- eval

(defun pod-emacs--eval-string (code)
  "Read and evaluate all top-level forms in CODE, returning the last value."
  (let ((forms nil) (pos 0) (len (length code)))
    (condition-case _
        (while (< pos len)
          (let ((res (read-from-string code pos)))
            (push (car res) forms)
            (setq pos (cdr res))))
      (end-of-file nil))
    (if forms
        (eval (cons 'progn (nreverse forms)) t)
      nil)))

(defun pod-emacs--funcall (f &rest args)
  "Call the elisp function named F with ARGS (already EDN-decoded values).
ARGS are real data, so nothing is string-spliced into elisp."
  (unless f (error "funcall: missing function name"))
  (apply (if (symbolp f) f (intern f)) args))

(defun pod-emacs--version ()
  "Build the version reply hash-table."
  (pod-emacs--ht :emacs-version emacs-version
                 :major-version emacs-major-version
                 :exec (or (car command-line-args) "emacs")))

(defun pod-emacs--eval-clj (code)
  "Compile Clojure source CODE with cljbang and evaluate it in this Emacs.
Definitions persist for the life of the emacs child, so one call can
`defn' helpers that later calls use."
  (cljbang-eval-string code))

;; Core's own namespace, registered like any feature module.
(pod-emacs-register
 "pod.kpassapk.emacs"
 `(("eval"      . ,#'pod-emacs--eval-string)
   ("eval-clj"  . ,#'pod-emacs--eval-clj)
   ("eval-file" . ,(lambda (path)
                     (load (expand-file-name path) nil t t)
                     (file-name-nondirectory path)))
   ("funcall"   . ,#'pod-emacs--funcall)
   ("install!"  . ,#'pod-emacs--install)
   ("version"   . ,#'pod-emacs--version)))

;; The `clj!' macro runs on the babashka side: it captures its body as forms,
;; resolves ~/~@ interpolations, pr-strs the result and sends it to `eval-clj'
;; above, where cljbang compiles and runs it.  The walk is template-style, not
;; syntax-quote: symbols stay bare, so (find-file ...) is not qualified into
;; some babashka namespace.  #(...) arrives from the reader as fn*, which
;; cljbang doesn't compile, so the walk rewrites it to fn.
(pod-emacs-register-client
 "pod.kpassapk.emacs"
 '(("clj!" . "
(defn- -clj-unquote? [f]
  (and (seq? f) (= 'clojure.core/unquote (first f))))

(defn- -clj-splice? [f]
  (and (seq? f) (= 'clojure.core/unquote-splicing (first f))))

(declare -clj-quote)

(defn- -clj-parts [coll]
  (cons 'clojure.core/concat
        (map (fn [x]
               (if (-clj-splice? x)
                 (second x)
                 (list 'clojure.core/list (-clj-quote x))))
             coll)))

(defn- -clj-quote [form]
  (cond
    (-clj-unquote? form) (second form)
    (seq? form) (cond
                  (empty? form) '(clojure.core/list)
                  (= 'fn* (first form)) (-clj-parts (cons 'fn (rest form)))
                  :else (-clj-parts form))
    (vector? form) (list 'clojure.core/vec (-clj-parts form))
    (map? form) (list 'clojure.core/apply 'clojure.core/array-map
                      (-clj-parts (apply concat form)))
    (set? form) (list 'clojure.core/set (-clj-parts (seq form)))
    :else (list 'quote form)))

(defmacro clj!
  \"Run BODY (Clojure forms) inside Emacs via cljbang; return the last value.
  ~x interpolates a babashka value into the code; ~@xs splices a collection.
  Use el/name to call Emacs Lisp directly, e.g. (el/buffer-list).
  Definitions persist across calls for the life of the pod session.\"
  [& body]
  (list 'pod.kpassapk.emacs/eval-clj
        (list 'clojure.core/binding
              '[clojure.core/*print-length* nil clojure.core/*print-level* nil]
              (list 'clojure.core/apply 'clojure.core/str
                    (list 'clojure.core/interpose \"\\n\"
                          (list 'clojure.core/map 'clojure.core/pr-str
                                (cons 'clojure.core/list
                                      (map -clj-quote body))))))))
")))

(defun pod-emacs--dispatch (var args)
  "Run pod VAR (a fully-qualified \"ns/name\" string) with ARGS (a list)."
  (let* ((slash (string-search "/" var))
         (ns    (and slash (substring var 0 slash)))
         (name  (and slash (substring var (1+ slash))))
         (handler (alist-get name
                             (alist-get ns pod-emacs--namespaces nil nil #'equal)
                             nil nil #'equal)))
    (if handler
        (apply handler args)
      (error "Unknown pod var: %s" var))))

;;;; ---------------------------------------------------------------- dispatch

(defun pod-emacs--invoke (msg id)
  "Handle an invoke MSG with request ID."
  (let* ((var (gethash "var" msg))
         (args-edn (gethash "args" msg))
         (args (when (and args-edn (> (length args-edn) 0))
                 (append (cljbang-edn-read-string args-edn) nil))))
    (condition-case err
        (let ((result (pod-emacs--dispatch var args)))
          (pod-emacs--send
           (pod-emacs--ht "id" id
                          "value" (pod-emacs--encode-edn result)
                          "status" (list "done"))))
      (error
       (pod-emacs--send
        (pod-emacs--ht
         "id" id
         "ex-message" (error-message-string err)
         "ex-data" (pod-emacs--encode-edn
                    (pod-emacs--ht :type (symbol-name (car err))
                                   :var (or var :unknown)))
         "status" (list "done" "error")))))))

(defun pod-emacs--handle (msg)
  "Dispatch a decoded protocol MSG. Return :shutdown to stop the loop."
  (let ((op (gethash "op" msg))
        (id (gethash "id" msg)))
    (cond
     ((equal op "describe")
      (pod-emacs--send (pod-emacs--describe-reply)) nil)
     ((equal op "invoke")
      (pod-emacs--invoke msg id) nil)
     ((equal op "shutdown")
      (pod-emacs--send (pod-emacs--ht "id" id "status" (list "done")))
      :shutdown)
     (t
      (when id
        (pod-emacs--send
         (pod-emacs--ht "id" id
                        "ex-message" (format "Unknown op: %s" op)
                        "status" (list "done" "error"))))
      nil))))

;;;; ---------------------------------------------------------------- main loop

(defun pod-emacs--drain ()
  "Process all complete bencode messages buffered in the pod input buffer.
Return nil to request shutdown, t to keep running.  The input buffer is
re-entered for every decode rather than assumed current: a handler that
visits a file (`find-file', org-babel, ...) leaves that buffer current, and
the protocol must never read bencode from — or splice bytes into — user
buffers."
  (let ((buf (get-buffer pod-emacs--in-buffer-name))
        (keep t) (more t))
    (while more
      (let ((msg (with-current-buffer buf
                   (when (> (buffer-size) 0)
                     (goto-char (point-min))
                     (condition-case _
                         (prog1 (bencode-decode-from-buffer
                                 :dict-type 'hash-table :list-type 'list)
                           (delete-region (point-min) (point)))
                       ;; partial message: wait for more bytes from stdin
                       (bencode-end-of-file nil))))))
        (if (null msg)
            (setq more nil)
          (when (eq (pod-emacs--handle msg) :shutdown)
            (setq keep nil more nil)))))
    keep))

(defun pod-emacs-main ()
  "Run the pod protocol loop over base64-framed stdin/stdout.
Only core is loaded at startup; packages a script needs arrive via `install!'."
  (set-binary-mode 'stdin t)
  (let ((buf (get-buffer-create pod-emacs--in-buffer-name))
        ;; stdout is the protocol channel: anything user elisp writes there
        ;; (princ, print, pp) would corrupt the base64 framing and kill the
        ;; session, so route `standard-output' to stderr for the whole loop.
        ;; Replies are unaffected: `pod-emacs--send' writes via
        ;; `send-string-to-terminal', which bypasses `standard-output'.
        (standard-output #'external-debugging-output))
    (with-current-buffer buf (set-buffer-multibyte nil))
    (let ((line nil) (running t))
      (while (and running
                  (setq line (condition-case _
                                 (read-string "")
                               ((end-of-file error) nil))))
        (when (> (length line) 0)
          ;; re-enter the input buffer per line: a handler may have left a
          ;; user buffer current (see pod-emacs--drain)
          (with-current-buffer buf
            (goto-char (point-max))
            (insert (base64-decode-string line))))
        (setq running (pod-emacs--drain)))))
  (kill-emacs 0))

(provide 'pod-emacs)
;;; pod-emacs.el ends here
