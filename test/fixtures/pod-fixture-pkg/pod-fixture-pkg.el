;;; pod-fixture-pkg.el --- A package installed from git -*- lexical-binding: t; -*-

;; Version: 1.0
;; Package-Requires: ((cljbang "0.0.9") (seq "2.0") (pod-fixture-dep "1.0"))

;;; Commentary:

;; Test fixture: the suite commits this directory to a temporary git repo and
;; installs it with `:vc'.  Its requirements cover the three ways one can be
;; met: cljbang is bundled with the pod, seq is built into Emacs, and
;; pod-fixture-dep comes from the directory archive next to this fixture.

;;; Code:

(require 'cljbang)
(require 'pod-fixture-dep)

(defun pod-fixture-pkg-hello ()
  "Return a marker the suite checks for, built from the dependency's."
  (concat "pkg+" (pod-fixture-dep-hello)))

(provide 'pod-fixture-pkg)
;;; pod-fixture-pkg.el ends here
