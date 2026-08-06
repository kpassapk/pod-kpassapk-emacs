#!/usr/bin/env bb
;; Tap an org file's outline into Portal and browse it as data.
;;
;;   bb examples/org-portal.bb [path/to/file.org]
;;
;; The reading is done by cljbang-org, an ordinary Emacs package called through
;; `emacs/clj!' — no pod namespace in front of it.  It returns flat heading maps
;; and leaves the shape to the caller, so the nesting Portal wants is a `tree'
;; call here rather than a second reader on the Emacs side.

(require '[babashka.deps :as deps]
         '[babashka.pods :as pods]
         '[clojure.java.io :as io])

(deps/add-deps '{:deps {djblue/portal {:mvn/version "0.65.0"}}})

(require '[portal.api :as p])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "target" "release" "pod-kpassapk-emacs")))
(def org-file (or (first *command-line-args*)
                  (.getPath (io/file here "sample.org"))))

(pods/load-pod [pod])
(require '[pod.kpassapk.emacs :as emacs])

;; Install cljbang-org on first run, the way the pod installs its own
;; third-party elisp, and load it.
(emacs/clj!
 (el/require 'package)
 (el/package-initialize)
 (when-not (el/locate-library "cljbang-org")
   (el/package-vc-install "https://github.com/kpassapk/cljbang-org"))
 (el/require 'cljbang-org))

;; Defined once inside Emacs; definitions persist for the pod session.
;; `headings' is flat, `tree' nests it by :level, and the #+TITLE: comes from
;; `keywords' — three small readers rather than one shape baked into the read.
(emacs/clj!
 (require '[cljbang.org :as-alias org])

 (defn outline [file]
   {:file file
    :title (first (:title (org/keywords file)))
    :children (org/tree (org/headings file {:body? true}))}))

(def p (p/open))
(add-tap #'p/submit)

(tap> (emacs/clj! (outline ~org-file)))
