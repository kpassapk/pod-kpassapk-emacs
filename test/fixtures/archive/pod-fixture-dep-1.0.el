;;; pod-fixture-dep.el --- A dependency served from a directory archive -*- lexical-binding: t; -*-

;; Version: 1.0

;;; Commentary:

;; Test fixture: the package that pod-fixture-pkg requires from an archive.
;; The archive is this directory; `archive-contents' next to this file lists
;; it, so the suite can install a dependency without the network.

;;; Code:

(defun pod-fixture-dep-hello ()
  "Return a marker the suite checks for."
  "dep")

(provide 'pod-fixture-dep)
;;; pod-fixture-dep.el ends here
