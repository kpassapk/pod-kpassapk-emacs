;;; pod-emacs-util.el --- shared helpers for pod-kpassapk-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; Small helpers shared by the protocol brain (pod-emacs) and feature modules
;; (pod-emacs-org).  Kept separate to avoid a circular require.
;;
;; The EDN printer lives here.  It used to be parseedn's, which is GPL-3.0 and
;; so could not travel inside an EPL-1.0 binary; this one keeps the value
;; mapping parseedn gave us, because that mapping is the pod's wire contract.
;;; Code:

(require 'subr-x)

(defun pod-emacs--ht (&rest pairs)
  "Build an `equal' hash-table from PAIRS (k1 v1 k2 v2 ...).
String keys -> bencode dict keys; keyword keys -> EDN map keys."
  (let ((h (make-hash-table :test 'equal :size (max 1 (/ (length pairs) 2)))))
    (while pairs
      (puthash (car pairs) (cadr pairs) h)
      (setq pairs (cddr pairs)))
    h))

(defun pod-emacs--cljbang-set-p (val)
  "Whether VAL is a cljbang set, a record holding its members as hash keys.
Recognized by its record tag rather than by calling into cljbang, so this
file needs nothing loaded to print one."
  (and (recordp val) (eq (aref val 0) 'cljbang-set)))

;;;; ------------------------------------------------------------ EDN printing
;;
;; Elisp writes a map three ways -- hash-table, alist, plist -- and a caller
;; who wrote `(:a 1)' means a map, not a list.  So all three cross as EDN maps,
;; and the tests pin that down.  A list is only a list when it is neither: an
;; alist is a proper list of conses with atoms for keys, a plist a proper list
;; of keyword/value pairs, and `("a" 1)' is neither, so it stays a list.

(defconst pod-emacs--edn-escapes
  '((?\" . "\\\"") (?\\ . "\\\\") (?\n . "\\n")
    (?\r . "\\r") (?\t . "\\t") (?\f . "\\f"))
  "Characters that may not stand raw inside an EDN string, and their escapes.")

(defun pod-emacs--edn-string (s)
  "S as an EDN string literal, quotes and escapes included."
  (concat "\""
          (mapconcat (lambda (c)
                       (or (cdr (assq c pod-emacs--edn-escapes))
                           (char-to-string c)))
                     s "")
          "\""))

(defun pod-emacs--alist-p (val)
  "Whether VAL is a proper list of conses whose keys are atoms."
  (let ((l val) (ok t))
    (while (and ok (consp l))
      (if (and (consp (car l)) (atom (caar l)))
          (setq l (cdr l))
        (setq ok nil)))
    (and ok (null l) (not (null val)))))

(defun pod-emacs--plist-p (val)
  "Whether VAL is a proper list of keyword/value pairs."
  (let ((l val) (ok t))
    (while (and ok (consp l))
      (if (and (keywordp (car l)) (consp (cdr l)))
          (setq l (cddr l))
        (setq ok nil)))
    (and ok (null l) (not (null val)))))

(defun pod-emacs--edn-seq (vals)
  "VALS as EDN, space-separated."
  (mapconcat #'pod-emacs--edn vals " "))

(defun pod-emacs--edn-pairs (pairs)
  "PAIRS, a list of (key . value), as the inside of an EDN map."
  (mapconcat (lambda (kv)
               (concat (pod-emacs--edn (car kv)) " " (pod-emacs--edn (cdr kv))))
             pairs ", "))

(defun pod-emacs--edn (val)
  "VAL as EDN.
Anything EDN has no form for -- a buffer, a function, a dotted pair --
becomes its `%S' string where it stands.  In place is the point: one odd
value deep in a reply costs that value and not the whole reply, so
`{:ok 1 :buf (el/current-buffer)}' still comes back as a map."
  (cond
   ((null val) "nil")
   ((eq val t) "true")
   ((stringp val) (pod-emacs--edn-string val))
   ((numberp val) (format "%s" val))
   ((symbolp val) (symbol-name val))          ; keywords carry their own colon
   ((pod-emacs--cljbang-set-p val)
    (concat "#{" (pod-emacs--edn-seq (hash-table-keys (aref val 1))) "}"))
   ((vectorp val) (concat "[" (pod-emacs--edn-seq (append val nil)) "]"))
   ((hash-table-p val)
    (let (pairs)
      (maphash (lambda (k v) (push (cons k v) pairs)) val)
      (concat "{" (pod-emacs--edn-pairs (nreverse pairs)) "}")))
   ((pod-emacs--alist-p val) (concat "{" (pod-emacs--edn-pairs val) "}"))
   ((pod-emacs--plist-p val)
    (let (pairs (l val))
      (while l
        (push (cons (car l) (cadr l)) pairs)
        (setq l (cddr l)))
      (concat "{" (pod-emacs--edn-pairs (nreverse pairs)) "}")))
   ((proper-list-p val) (concat "(" (pod-emacs--edn-seq val) ")"))
   (t (pod-emacs--edn-string (format "%S" val)))))

(defun pod-emacs--encode-edn (val)
  "Encode elisp VAL as an EDN string, falling back to a string repr."
  (condition-case _
      (pod-emacs--edn val)
    (error (pod-emacs--edn-string (format "%S" val)))))

(provide 'pod-emacs-util)
;;; pod-emacs-util.el ends here
