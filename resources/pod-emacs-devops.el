;;; pod-emacs-devops.el --- devops.el -> EDN for pod-babashka-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; Bridge the `devops' package (org-babel tangle/execute to remote TRAMP
;; targets) into the pod.  devops' commands are interactive and operate on the
;; current org buffer at point; here we drive its noninteractive engine over a
;; file passed from babashka.
;;
;;   pod.babashka.emacs.devops/tangle      (OPTS)  -> [{:tag :target :files}]
;;   pod.babashka.emacs.devops/execute     (OPTS)  -> <block result>
;;   pod.babashka.emacs.devops/targets     (OPTS)  -> [{:tag :target}]
;;   pod.babashka.emacs.devops/src-blocks  (OPTS)  -> [{:index :targets ..}]
;;
;; OPTS is an EDN map.  `tangle' recognizes:
;;   :file       <string>  path to the org file (required)
;;   :heading    <string>  exact headline title to tangle
;;   :custom-id  <string>  CUSTOM_ID property of the heading to tangle
;;   :all        <bool>    tangle every target-tagged heading instead
;;
;; `execute' recognizes:
;;   :file     <string>  path to the org file (required)
;;   :name     <string>  run the block whose `#+name:' matches
;;   :index    <int>     run the Nth src block, 0-based, in document order
;;
;; `targets' and `src-blocks' recognize :file only.  `targets' lists the
;; #+TARGET tag->target map every selector resolves against; `src-blocks'
;; lists each block with the target(s) and tangle path its heading resolves to.
;;
;; A block's enclosing heading target tag is resolved to a :dir and injected
;; into its babel params by `devops''s advice, so the block runs on its target.
;;; Code:

(require 'org)
(require 'devops)
(require 'pod-emacs)
(require 'pod-emacs-org)
(require 'pod-emacs-util)

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
Pass :heading or :custom-id to tangle one headline, or :all to tangle every
target-tagged headline in the file."
  (let ((file      (pod-emacs-devops--opt opts :file))
        (heading   (pod-emacs-devops--opt opts :heading))
        (custom-id (pod-emacs-devops--opt opts :custom-id))
        (all       (pod-emacs-devops--opt opts :all)))
    (unless file (error "tangle: missing :file"))
    (pod-emacs-org--with-file file
      (let ((org-confirm-babel-evaluate nil))
        (pod-emacs-devops--results->edn
         (cond
          (all       (devops-tangle-all (current-buffer)))
          (custom-id (devops-tangle-custom-id (current-buffer) custom-id))
          (heading   (devops-tangle-headline (current-buffer) heading))
          (t (error "tangle: pass :heading, :custom-id or :all"))))))))

(defun pod-emacs-devops--targets->edn (alist)
  "Turn ALIST of (TAG . TARGET) into a vector of {:tag :target} maps."
  (vconcat (mapcar (lambda (pair)
                     (pod-emacs--ht :tag (car pair) :target (cdr pair)))
                   alist)))

(defun pod-emacs-devops-targets (&optional opts)
  "List the #+TARGET tag->target mappings declared in an org file.
OPTS is an EDN map; :file <string> is required.  Returns a vector of
{:tag :target} -- the same tags `tangle' and `execute' resolve against."
  (let ((file (pod-emacs-devops--opt opts :file)))
    (unless file (error "targets: missing :file"))
    (pod-emacs-org--with-file file
      (pod-emacs-devops--targets->edn (devops-target-tag-alist)))))

(defun pod-emacs-devops--block-targets (tangle)
  "Resolve the enclosing heading's devops targets for the block at point.
TANGLE is the block's raw :tangle value.  Returns a vector of {:tag :target
:path}, where :path is TANGLE joined onto the target (omitted when TANGLE is
no/yes or already a TRAMP path).  Nil when the heading has no #+TARGET tag."
  (let ((matches (save-excursion (devops--heading-target-tags)))
        (rel (and tangle
                  (not (member tangle '("no" "yes")))
                  (not (tramp-tramp-file-p tangle))
                  tangle)))
    (when matches
      (vconcat
       (mapcar (lambda (pair)
                 (let ((h (pod-emacs--ht :tag (car pair) :target (cdr pair))))
                   (when rel
                     (puthash :path (devops--join-target (cdr pair) rel) h))
                   h))
               matches)))))

(defun pod-emacs-devops-src-blocks (&optional opts)
  "List src blocks in an org file, each tagged with its devops target(s).
OPTS is an EDN map; :file <string> is required.  Like
`pod.babashka.emacs.org/src-blocks', but every block also carries:
  :tangle   <string>  the block's raw :tangle header arg, or nil
  :targets  <vector>  {:tag :target :path} resolved from the enclosing
                      heading; omitted when the heading has no #+TARGET tag,
                      marking the block local-only."
  (let ((file (pod-emacs-devops--opt opts :file)))
    (unless file (error "src-blocks: missing :file"))
    (pod-emacs-org--with-file file
      (let ((acc '()) (i 0))
        (org-babel-map-src-blocks nil
          (let* ((info    (org-babel-get-src-block-info t))
                 (name    (nth 4 info))
                 (params  (nth 2 info))
                 (tangle  (let ((tg (cdr (assq :tangle params))))
                            (and tg (pod-emacs-org--str (format "%s" tg)))))
                 (targets (pod-emacs-devops--block-targets tangle))
                 (node    (pod-emacs--ht
                           :index i
                           :name (and name (> (length name) 0)
                                      (pod-emacs-org--str name))
                           :lang (pod-emacs-org--str (nth 0 info))
                           :tangle tangle
                           :header-args (pod-emacs-org--header-args params))))
            (when targets (puthash :targets targets node))
            (push node acc)
            (setq i (1+ i))))
        (vconcat (nreverse acc))))))

;;;; ----------------------------------------------------------- registration

(pod-emacs-register
 "pod.babashka.emacs.devops"
 `(("tangle"     . ,#'pod-emacs-devops-tangle)
   ("targets"    . ,#'pod-emacs-devops-targets)
   ("src-blocks" . ,#'pod-emacs-devops-src-blocks)))

(provide 'pod-emacs-devops)
;;; pod-emacs-devops.el ends here
