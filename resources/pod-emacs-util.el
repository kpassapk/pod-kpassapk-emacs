;;; pod-emacs-util.el --- shared helpers for pod-kpassapk-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; Small helpers shared by the protocol brain (pod-emacs) and feature modules
;; (pod-emacs-org).  Kept separate to avoid a circular require.
;;; Code:

(require 'parseedn)

(defun pod-emacs--ht (&rest pairs)
  "Build an `equal' hash-table from PAIRS (k1 v1 k2 v2 ...).
String keys -> bencode dict keys; keyword keys -> EDN map keys via parseedn."
  (let ((h (make-hash-table :test 'equal :size (max 1 (/ (length pairs) 2)))))
    (while pairs
      (puthash (car pairs) (cadr pairs) h)
      (setq pairs (cddr pairs)))
    h))

(defun pod-emacs--cljbang-set-p (val)
  "Whether VAL is a cljbang set, a record holding its members as hash keys.
Recognized by its record tag rather than by calling into cljbang, which
loads lazily and may not be there at all."
  (and (recordp val) (eq (aref val 0) 'cljbang-set)))

(defun pod-emacs--edn-atom (val)
  "VAL if parseedn can print it, otherwise its `%S' string."
  (condition-case _
      (progn (parseedn-print-str val) val)
    (error (format "%S" val))))

(defun pod-emacs--edn-value (val)
  "VAL with everything parseedn cannot print rewritten so that it can.
A cljbang set becomes an EDN set; anything else parseedn refuses -- a
buffer, a function, another record -- becomes its `%S' string where it
stands.  In place is the point: the printer is all-or-nothing, so one
odd value anywhere in a reply would otherwise send the whole thing back
as one long string.  `clj!' made that easy to hit, since a heading map
from cljbang.org carries its tags as a set."
  (cond
   ((pod-emacs--cljbang-set-p val)
    (let (members)
      (maphash (lambda (k _v) (push (pod-emacs--edn-value k) members)) (aref val 1))
      (list 'edn-set (apply #'vector (nreverse members)))))
   ((hash-table-p val)
    (let ((h (make-hash-table :test (hash-table-test val)
                              :size (max 1 (hash-table-count val)))))
      (maphash (lambda (k v)
                 (puthash (pod-emacs--edn-value k) (pod-emacs--edn-value v) h))
               val)
      h))
   ((vectorp val)
    (apply #'vector (mapcar #'pod-emacs--edn-value (append val nil))))
   ((consp val)
    (if (listp (cdr val))
        (mapcar #'pod-emacs--edn-value val)
      (cons (pod-emacs--edn-value (car val)) (pod-emacs--edn-value (cdr val)))))
   (t (pod-emacs--edn-atom val))))

(defun pod-emacs--encode-edn (val)
  "Encode elisp VAL as an EDN string, falling back to a string repr."
  (condition-case _
      (parseedn-print-str (pod-emacs--edn-value val))
    (error (parseedn-print-str (format "%S" val)))))

(provide 'pod-emacs-util)
;;; pod-emacs-util.el ends here
