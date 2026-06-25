;;; pod-emacs-devops.el --- devops.el -> EDN for pod-babashka-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; Bridge the `devops' package (org-babel tangle to remote TRAMP targets) into
;; the pod.  devops' commands are interactive and operate on the current org
;; buffer at point; here we drive its noninteractive engine over a file passed
;; from babashka.
;;
;;   pod.babashka.emacs.devops/tangle  (OPTS)  -> [{:tag :target :files}]
;;
;; OPTS is an EDN map; recognized keys:
;;   :file     <string>  path to the org file (required)
;;   :heading  <string>  exact headline title to tangle
;;   :all      <bool>    tangle every target-tagged heading instead
;;; Code:

(require 'org)
(require 'devops)
(require 'pod-emacs)
(require 'pod-emacs-util)

(defmacro pod-emacs-devops--with-file (path &rest body)
  "Open org file PATH in a temp org-mode buffer and run BODY.
`default-directory' is set to the file's directory so relative :tangle
paths resolve as they would when the file is visited."
  (declare (indent 1))
  `(let ((p (expand-file-name ,path)))
     (unless (file-readable-p p)
       (error "Cannot read org file: %s" p))
     (with-temp-buffer
       (insert-file-contents p)
       (setq default-directory (or (file-name-directory p) default-directory))
       (let ((org-inhibit-startup t)
             (org-element-use-cache nil)
             (org-mode-hook nil))
         (delay-mode-hooks (org-mode)))
       ,@body)))

(defun pod-emacs-devops--opt (opts key)
  "Look up KEY in OPTS, which may be a hash-table (from parseedn) or nil."
  (when (hash-table-p opts) (gethash key opts)))

(defun pod-emacs-devops--results->edn (results)
  "Turn RESULTS, a list of (TAG TARGET N), into a list of EDN maps."
  (mapcar (lambda (r)
            (pod-emacs--ht :tag    (nth 0 r)
                           :target (nth 1 r)
                           :files  (nth 2 r)))
          results))

(defun pod-emacs-devops-tangle (&optional opts)
  "Tangle an org file's source blocks to their devops TRAMP target(s).
OPTS is an EDN map (see commentary).  Returns a list of {:tag :target :files}.
Pass :heading to tangle one headline, or :all to tangle every target-tagged
headline in the file."
  (let ((file    (pod-emacs-devops--opt opts :file))
        (heading (pod-emacs-devops--opt opts :heading))
        (all     (pod-emacs-devops--opt opts :all)))
    (unless file (error "tangle: missing :file"))
    (pod-emacs-devops--with-file file
      (let ((org-confirm-babel-evaluate nil))
        (pod-emacs-devops--results->edn
         (cond
          (all     (devops-tangle-all (current-buffer)))
          (heading (devops-tangle-headline (current-buffer) heading))
          (t (error "tangle: pass :heading or :all"))))))))

;;;; ----------------------------------------------------------- registration

(pod-emacs-register
 "pod.babashka.emacs.devops"
 `(("tangle" . ,#'pod-emacs-devops-tangle)))

(provide 'pod-emacs-devops)
;;; pod-emacs-devops.el ends here
