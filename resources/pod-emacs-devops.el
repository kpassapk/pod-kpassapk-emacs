;;; pod-emacs-devops.el --- devops.el -> EDN for pod-kpassapk-emacs -*- lexical-binding: t; -*-
;;; Commentary:
;; Bridge the `devops' package (org-babel tangle/execute to remote TRAMP
;; targets) into the pod.  devops' commands are interactive and operate on the
;; current org buffer at point; here we drive its noninteractive engine over a
;; file passed from babashka.
;;
;;   pod.kpassapk.emacs.devops/tangle      (OPTS)  -> [{:tag :target :files}]
;;   pod.kpassapk.emacs.devops/targets     (OPTS)  -> [{:tag :target :remote? ..}]
;;   pod.kpassapk.emacs.devops/src-blocks  (OPTS)  -> [{:index :targets ..}]
;;
;; There is deliberately no `execute': `targets' hands the caller a full
;; connection spec (:host :user :port :hops :dir), so running a block's
;; :body on its target is the caller's job -- see examples/devops.bb,
;; which does it over ssh from babashka.  `tangle' stays in Emacs because
;; writing through TRAMP is what org-babel-tangle already does well.
;;
;; OPTS is an EDN map.  `tangle' recognizes:
;;   :file       <string>  path to the org file (required)
;;   :heading    <string>  exact headline title to tangle
;;   :custom-id  <string>  CUSTOM_ID property of the heading to tangle
;;   :all        <bool>    tangle every target-tagged heading instead
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

(defun pod-emacs-devops--hop->edn (vec)
  "Turn a `tramp-file-name' VEC into a hop spec map for one SSH hop.
Carries every connection slot TRAMP dissects -- :method :user :domain
:host :port -- but not :localname (the working dir lives on the target
spec, not the intermediate hops)."
  (pod-emacs--ht
   :method (pod-emacs-org--str (tramp-file-name-method vec))
   :user   (pod-emacs-org--str (tramp-file-name-user vec))
   :domain (pod-emacs-org--str (tramp-file-name-domain vec))
   :host   (pod-emacs-org--str (tramp-file-name-host vec))
   :port   (pod-emacs-org--str (tramp-file-name-port vec))))

(defun pod-emacs-devops--hops (vec)
  "Ordered vector of hop specs to reach TRAMP VEC's host.
TRAMP encodes a multi-hop path like
\"/ssh:jump|ssh:bird@bastion#2222|sudo:root@target:/etc\" by keeping the
final hop in VEC's own slots and the earlier hops packed into VEC's :hop
slot as a `|'-separated string.  This unpacks that chain so an external
tool can replicate TRAMP's proxying: walk the vector in order (ssh into
hop 1, from there hop 2, ...) until the last element, which is the target
host itself.  Each element is a `pod-emacs-devops--hop->edn' map.  A
single-hop target yields a one-element vector."
  (let* ((hop  (tramp-file-name-hop vec))
         (segs (and hop (split-string hop tramp-postfix-hop-format t)))
         (inter (mapcar (lambda (seg)
                          (pod-emacs-devops--hop->edn
                           (tramp-dissect-hop-name
                            (concat seg tramp-postfix-host-format))))
                        segs)))
    (vconcat (append inter (list (pod-emacs-devops--hop->edn vec))))))

(defun pod-emacs-devops--target-spec (target base-dir)
  "Parse a #+TARGET string TARGET into an execution spec hash-table.
The spec tells a caller outside Emacs where a block's code should run,
losing nothing `tramp-dissect-file-name' knows:

  :remote?  t for a TRAMP target, nil for a plain local directory.
  :method :user :domain :host :port
            the final hop -- the target host's connection slots; remote
            only.  Nil for slots the target omits.
  :hops     ordered vector of {:method :user :domain :host :port}, the
            full proxy chain to walk (intermediate jump hosts, then the
            target); remote only.  Single-hop targets give a one-element
            vector; multi-hop \"/ssh:a|ssh:b:/dir\" targets give one entry
            per hop, so an external tool can reproduce TRAMP's routing.
  :dir      the working directory.  For remote targets this is the TRAMP
            localname (\"\" for a bare \"/ssh:host:\" prefix, meaning the
            login dir).  For local targets it is TARGET expanded against
            BASE-DIR, matching how `devops' resolves relative local
            targets against the org file's directory."
  (if (tramp-tramp-file-p target)
      (let ((vec (tramp-dissect-file-name target)))
        (pod-emacs--ht
         :remote? t
         :method (pod-emacs-org--str (tramp-file-name-method vec))
         :user   (pod-emacs-org--str (tramp-file-name-user vec))
         :domain (pod-emacs-org--str (tramp-file-name-domain vec))
         :host   (pod-emacs-org--str (tramp-file-name-host vec))
         :port   (pod-emacs-org--str (tramp-file-name-port vec))
         :hops   (pod-emacs-devops--hops vec)
         :dir    (pod-emacs-org--str (tramp-file-name-localname vec))))
    (pod-emacs--ht
     :remote? nil
     :dir (pod-emacs-org--str (expand-file-name target base-dir)))))

(defun pod-emacs-devops--targets->edn (alist base-dir)
  "Turn ALIST of (TAG . TARGET) into a vector of target-spec maps.
Each map carries :tag, :target (the raw #+TARGET value) and the parsed
execution spec (:remote?, :host, :user, :dir ...); see
`pod-emacs-devops--target-spec'.  BASE-DIR resolves relative local targets."
  (vconcat
   (mapcar (lambda (pair)
             (let ((spec (pod-emacs-devops--target-spec (cdr pair) base-dir)))
               (puthash :tag    (pod-emacs-org--str (car pair)) spec)
               (puthash :target (pod-emacs-org--str (cdr pair)) spec)
               spec))
           alist)))

(defun pod-emacs-devops-targets (&optional opts)
  "List the #+TARGET tag->target mappings declared in an org file.
OPTS is an EDN map; :file <string> is required.  Returns a vector of
maps, one per tag, each carrying :tag, :target (the raw #+TARGET value)
and a parsed execution spec (:remote?, :host, :user, :dir ...) so a
caller outside Emacs can run a block's code on the right target -- see
`pod-emacs-devops--target-spec'.  These are the same tags `tangle' and
`execute' resolve against."
  (let ((file (pod-emacs-devops--opt opts :file)))
    (unless file (error "targets: missing :file"))
    (let ((base-dir (file-name-directory (expand-file-name file))))
      (pod-emacs-org--with-file file
        (pod-emacs-devops--targets->edn (devops-target-tag-alist) base-dir)))))

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
`pod.kpassapk.emacs.org/src-blocks', but every block also carries:
  :heading  <string>  the enclosing headline's title, or nil before the
                      first heading; feed it back to `tangle' as :heading
  :body     <string>  the block's source text, ready to run on the target
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
                 (heading (save-excursion
                            (when (ignore-errors (org-back-to-heading t))
                              (pod-emacs-org--str (org-get-heading t t t t)))))
                 (tangle  (let ((tg (cdr (assq :tangle params))))
                            (and tg (pod-emacs-org--str (format "%s" tg)))))
                 (targets (pod-emacs-devops--block-targets tangle))
                 (node    (pod-emacs--ht
                           :index i
                           :name (and name (> (length name) 0)
                                      (pod-emacs-org--str name))
                           :lang (pod-emacs-org--str (nth 0 info))
                           :heading heading
                           :body (pod-emacs-org--str (nth 1 info))
                           :tangle tangle
                           :header-args (pod-emacs-org--header-args params))))
            (when targets (puthash :targets targets node))
            (push node acc)
            (setq i (1+ i))))
        (vconcat (nreverse acc))))))

;;;; ----------------------------------------------------------- registration

(pod-emacs-register
 "pod.kpassapk.emacs.devops"
 `(("tangle"     . ,#'pod-emacs-devops-tangle)
   ("targets"    . ,#'pod-emacs-devops-targets)
   ("src-blocks" . ,#'pod-emacs-devops-src-blocks)))

(provide 'pod-emacs-devops)
;;; pod-emacs-devops.el ends here
