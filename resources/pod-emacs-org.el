;;; pod-emacs-org.el --- org-mode -> EDN for pod-kpassapk-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; The flagship feature: read an org file *through Emacs' real org-mode* and
;; return its structure as EDN.  A babashka driver can then work with the
;; outline as ordinary Clojure data.
;;
;;   pod.kpassapk.emacs.org/outline    -> nested headline tree
;;   pod.kpassapk.emacs.org/headlines  -> flat headline list (document order)
;;   pod.kpassapk.emacs.org/to-edn     -> nested tree incl. each entry's body
;;
;; All three accept (PATH &optional OPTS).  OPTS is an EDN map; recognized keys:
;;   :max-level  <int>   only include headlines at or above this depth
;;; Code:

(require 'org)
(require 'org-element)
(require 'ob-lob nil t)
(require 'pod-emacs)
(require 'pod-emacs-util)
(require 'subr-x)

(defmacro pod-emacs-org--with-file (path &rest body)
  "Open org file PATH in a temp org-mode buffer and run BODY."
  (declare (indent 1))
  `(let ((p (expand-file-name ,path)))
     (unless (file-readable-p p)
       (error "Cannot read org file: %s" p))
     (with-temp-buffer
       (insert-file-contents p)
       (let ((org-inhibit-startup t)
             (org-element-use-cache nil)
             (org-mode-hook nil))
         (delay-mode-hooks (org-mode)))
       ,@body)))

(defun pod-emacs-org--str (s)
  "Strip text properties from string S (or return nil)."
  (when s (substring-no-properties s)))

(defun pod-emacs-org--props ()
  "Return the entry's drawer properties as a keyword-keyed hash-table, or nil."
  (let ((props (org-entry-properties (point) 'standard))
        (skip '("CATEGORY" "FILE" "BLOCKED" "ITEM" "PRIORITY" "TODO" "TAGS" "ALLTAGS"))
        (h (make-hash-table :test 'equal)))
    (dolist (kv props)
      (unless (member (car kv) skip)
        (puthash (intern (concat ":" (car kv)))
                 (pod-emacs-org--str (cdr kv)) h)))
    (when (> (hash-table-count h) 0) h)))

(defun pod-emacs-org--body ()
  "Return the trimmed body text of the entry at point (excludes subheadings)."
  (let ((body (string-trim (or (org-get-entry) ""))))
    (when (> (length body) 0) body)))

(defun pod-emacs-org--entry (include-body)
  "Build an EDN node hash-table for the headline at point.
Returns (LEVEL . NODE)."
  (let* ((level (org-current-level))
         (title (pod-emacs-org--str (org-get-heading t t t t)))
         (todo  (pod-emacs-org--str (org-get-todo-state)))
         (prio  (nth 3 (org-heading-components)))
         (tags  (mapcar #'pod-emacs-org--str (org-get-tags nil t)))
         (props (pod-emacs-org--props))
         (sched (pod-emacs-org--str (org-entry-get nil "SCHEDULED")))
         (dead  (pod-emacs-org--str (org-entry-get nil "DEADLINE")))
         (node  (pod-emacs--ht :level level :title (or title ""))))
    (when todo  (puthash :todo todo node))
    (when prio  (puthash :priority (char-to-string prio) node))
    (when tags  (puthash :tags (vconcat tags) node))
    (when props (puthash :properties props node))
    (when sched (puthash :scheduled sched node))
    (when dead  (puthash :deadline dead node))
    (puthash :begin (point) node)
    (when include-body
      (when-let* ((b (pod-emacs-org--body))) (puthash :body b node)))
    (cons level node)))

(defun pod-emacs-org--collect (include-body max-level)
  "Collect (LEVEL . NODE) pairs for all headlines, in document order."
  (delq nil
        (org-map-entries
         (lambda ()
           (when (or (null max-level) (<= (org-current-level) max-level))
             (pod-emacs-org--entry include-body))))))

(defun pod-emacs-org--nest (flat)
  "Turn FLAT list of (LEVEL . NODE) into a nested list of root nodes.
Children are appended under :children only when present (leaves omit the key)."
  (let ((roots '()) (stack '()))
    (dolist (item flat)
      (let ((level (car item)) (node (cdr item)))
        (while (and stack (>= (caar stack) level))
          (pop stack))
        (if stack
            (let ((parent (cdar stack)))
              (puthash :children
                       (append (gethash :children parent) (list node))
                       parent))
          (push node roots))
        (push (cons level node) stack)))
    (nreverse roots)))

(defun pod-emacs-org--opt (opts key)
  "Look up KEY in OPTS, which may be a hash-table (from parseedn) or nil."
  (when (hash-table-p opts) (gethash key opts)))

(defun pod-emacs-org--require-lang (lang)
  "Load the org-babel backend for LANG (a string) if not already present.
`emacs --batch -Q' loads no babel languages, so a block's backend must be
required before it can run.  Shell dialects map to `ob-shell'; everything
else falls back to `ob-LANG'."
  (when lang
    (let* ((l (downcase lang))
           (feature (pcase l
                      ((or "sh" "bash" "shell" "zsh" "fish" "csh" "ksh") 'ob-shell)
                      ((or "elisp" "emacs-lisp") 'ob-emacs-lisp)
                      (_ (intern (concat "ob-" l))))))
      (unless (fboundp (intern (concat "org-babel-execute:" l)))
        (require feature nil t)))))

(defun pod-emacs-org--block-positions ()
  "Return a list of buffer positions, one per src block, in document order.
Collected with `org-babel-map-src-blocks' rather than
`org-babel-next-src-block', which skips a block whose head sits at
point-min (Emacs 31)."
  (let ((positions '()))
    (org-babel-map-src-blocks nil
      (push (or (org-babel-where-is-src-block-head) (point)) positions))
    (nreverse positions)))

(defun pod-emacs-org--file-keyword (kw)
  "Return the value of buffer keyword KW (e.g. \"TITLE\"), or nil."
  (cadr (assoc-string kw (org-collect-keywords (list kw)) t)))

(defun pod-emacs-org--header-args (params)
  "Convert an org-babel PARAMS alist to a keyword-keyed hash-table for EDN.
Values are stringified so the whole reply stays EDN-encodable; duplicate
keys (e.g. several `:var') collapse to the last one."
  (let ((h (make-hash-table :test 'equal)))
    (dolist (kv params)
      (when (and (consp kv) (keywordp (car kv)))
        (let ((v (cdr kv)))
          (puthash (car kv)
                   (cond ((null v)     nil)
                         ((stringp v)  (pod-emacs-org--str v))
                         ((symbolp v)  (symbol-name v))
                         (t            (format "%S" v)))
                   h))))
    (when (> (hash-table-count h) 0) h)))

;;;; ----------------------------------------------------------- public vars

(defun pod-emacs-org-outline (path &optional opts)
  "Read org file PATH; return {:file :title :children [..]} as nested EDN."
  (pod-emacs-org--with-file path
    (let* ((max-level (pod-emacs-org--opt opts :max-level))
           (flat (pod-emacs-org--collect nil max-level))
           (roots (pod-emacs-org--nest flat)))
      (pod-emacs--ht :file (expand-file-name path)
                     :title (pod-emacs-org--file-keyword "TITLE")
                     :children roots))))

(defun pod-emacs-org-to-edn (path &optional opts)
  "Like `pod-emacs-org-outline' but also include each entry's :body text."
  (pod-emacs-org--with-file path
    (let* ((max-level (pod-emacs-org--opt opts :max-level))
           (flat (pod-emacs-org--collect t max-level))
           (roots (pod-emacs-org--nest flat)))
      (pod-emacs--ht :file (expand-file-name path)
                     :title (pod-emacs-org--file-keyword "TITLE")
                     :children roots))))

(defun pod-emacs-org-headlines (path &optional opts)
  "Read org file PATH; return a flat vector of headline nodes (document order)."
  (pod-emacs-org--with-file path
    (let* ((max-level (pod-emacs-org--opt opts :max-level))
           (flat (pod-emacs-org--collect nil max-level)))
      (vconcat (mapcar #'cdr flat)))))

(defun pod-emacs-org-src-blocks (path &optional _opts)
  "List every src block in org file PATH as EDN, in document order.
Returns a vector of nodes; each is a hash-table with:
  :index   <int>     0-based position in document order
  :name    <string>  the block's `#+name:', or nil
  :lang    <string>  the source language
  :begin   <int>     buffer position of the block head
  :header-args <map> the block's babel header arguments, or nil
  :body    <string>  the block's source text"
  (pod-emacs-org--with-file path
    (let ((acc '()) (i 0))
      (org-babel-map-src-blocks nil
        (let* ((info (org-babel-get-src-block-info t))
               (name (nth 4 info)))
          (push (pod-emacs--ht
                 :index i
                 :name (and name (> (length name) 0) (pod-emacs-org--str name))
                 :lang (pod-emacs-org--str (nth 0 info))
                 :begin (or (org-babel-where-is-src-block-head) (point))
                 :header-args (pod-emacs-org--header-args (nth 2 info))
                 :body (pod-emacs-org--str (nth 1 info)))
                acc)
          (setq i (1+ i))))
      (vconcat (nreverse acc)))))

(defun pod-emacs-org-call-blocks (path &optional _opts)
  "List every `#+call:' line in org file PATH as EDN, in document order.
A `#+call:' line is an org `babel-call' element: it invokes a *named* block
defined elsewhere (another heading, another file, or the library of babel).
`src-blocks' does not see these, so a driver that wants every runnable step
must merge both lists (sort by :begin).  Returns a vector of nodes:
  :index     <int>     0-based position among call blocks
  :name      <string>  the call line's own `#+name:', or nil
  :call      <string>  the name of the block being called
  :arguments <string>  the argument string inside the parens, or nil
  :begin     <int>     buffer position of the `#+call:' line itself
  :value     <string>  the raw call value, e.g. \"foo(BAR=baz)\""
  (pod-emacs-org--with-file path
    (let ((i -1))
      (vconcat
       (org-element-map (org-element-parse-buffer 'element) 'babel-call
         (lambda (el)
           (setq i (1+ i))
           (pod-emacs--ht
            :index i
            :name (pod-emacs-org--str (org-element-property :name el))
            :call (pod-emacs-org--str (org-element-property :call el))
            :arguments (pod-emacs-org--str (org-element-property :arguments el))
            ;; :post-affiliated is the `#+call:' line itself (skips a leading
            ;; `#+name:'), so it is the position `execute' must run from.
            :begin (or (org-element-property :post-affiliated el)
                       (org-element-property :begin el))
            :value (pod-emacs-org--str (org-element-property :value el)))))))))

(defun pod-emacs-org--goto-block (path opts)
  "Move point to the block in the current buffer selected by OPTS.
OPTS is an EDN map; recognized keys:
  :begin <int>     buffer position of the block (src or `#+call:' line); the
                   universal selector returned by `src-blocks'/`call-blocks'
  :name  <string>  the src block whose `#+name:' matches
  :index <int>     the Nth src block, 0-based, in document order
With none of these, require the file to hold exactly one src block.  PATH is
used only for error messages.  Signals when the selection is ambiguous,
missing, or out of range."
  (let ((begin (pod-emacs-org--opt opts :begin))
        (name  (pod-emacs-org--opt opts :name))
        (index (pod-emacs-org--opt opts :index)))
    (cond
     (begin
      (unless (and (integerp begin) (<= (point-min) begin) (<= begin (point-max)))
        (error "Block position %s out of range (buffer is %d..%d)"
               begin (point-min) (point-max)))
      (goto-char begin))
     (name
      (let ((pos (org-babel-find-named-block name)))
        (unless pos (error "No src block named: %s" name))
        (goto-char pos)))
     (index
      (let ((positions (pod-emacs-org--block-positions)))
        (unless (and (integerp index) (>= index 0) (< index (length positions)))
          (error "Block index %s out of range (file has %d)"
                 index (length positions)))
        (goto-char (nth index positions))))
     (t
      (let ((positions (pod-emacs-org--block-positions)))
        (cond
         ((null positions) (error "No src blocks in %s" path))
         ((= (length positions) 1) (goto-char (car positions)))
         (t (error "%d src blocks in %s; pass :name or :index"
                   (length positions) path))))))))

(defun pod-emacs-org--run-block (path opts)
  "Run the block selected by OPTS in the current buffer; return its result.
Handles both org-babel src blocks and `#+call:' lines (`babel-call'
elements): the element type at point decides which executor runs.
`default-directory' is set to PATH's directory so a relative `:dir' (e.g. the
`..' devops.el injects from a heading's #+TARGET) and any relative script path
resolve against the org file's location.  Point is left on the block that ran
\(its results, if any, land here)."
  (setq default-directory
        (or (file-name-directory (expand-file-name path)) default-directory))
  (let ((org-confirm-babel-evaluate nil))
    (pod-emacs-org--goto-block path opts)
    (if (eq 'babel-call (org-element-type (org-element-at-point)))
        ;; A `#+call:' line: resolve the named block it invokes, load that
        ;; block's language backend (the call inherits the callee's language),
        ;; then run it.
        (progn
          (when-let* ((info (org-babel-lob-get-info)))
            (pod-emacs-org--require-lang (car info)))
          (org-babel-lob-execute-maybe))
      (pod-emacs-org--require-lang (car (org-babel-get-src-block-info t)))
      (org-babel-execute-src-block))))

(defun pod-emacs-org-execute (path &optional opts)
  "Execute a src block or `#+call:' line in org file PATH; return its result.
OPTS is an EDN map selecting which block to run and how:
  :begin    <int>     buffer position of the block (src or `#+call:' line), as
                      returned by `src-blocks'/`call-blocks'; runs whichever
                      kind of block is at that position
  :name     <string>  the src block whose `#+name:' matches
  :index    <int>     the Nth src block, 0-based, in document order
  :in-place <bool>    when non-nil, visit PATH in a real buffer so the block's
                      results are written back and saved to disk; otherwise run
                      in a scratch buffer and discard any edits (read-only run)
If none of :begin/:name/:index is given and the file holds exactly one src
block, run it; otherwise signal an error so the caller disambiguates."
  (if (pod-emacs-org--opt opts :in-place)
      (let ((p (expand-file-name path)))
        (unless (file-readable-p p)
          (error "Cannot read org file: %s" p))
        (let ((buf (find-file-noselect p))
              (org-inhibit-startup t)
              (org-element-use-cache nil)
              (org-mode-hook nil))
          (unwind-protect
              (with-current-buffer buf
                (unless (derived-mode-p 'org-mode)
                  (delay-mode-hooks (org-mode)))
                (prog1 (pod-emacs-org--run-block p opts)
                  (save-buffer)))
            (kill-buffer buf))))
    (pod-emacs-org--with-file path
      (pod-emacs-org--run-block path opts))))

;;;; ----------------------------------------------------------- registration

(pod-emacs-register
 "pod.kpassapk.emacs.org"
 `(("outline"     . ,#'pod-emacs-org-outline)
   ("headlines"   . ,#'pod-emacs-org-headlines)
   ("to-edn"      . ,#'pod-emacs-org-to-edn)
   ("src-blocks"  . ,#'pod-emacs-org-src-blocks)
   ("call-blocks" . ,#'pod-emacs-org-call-blocks)
   ("execute"     . ,#'pod-emacs-org-execute)))

(provide 'pod-emacs-org)
;;; pod-emacs-org.el ends here
