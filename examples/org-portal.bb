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

;; Normally (pods/load-pod 'kpassapk/emacs "0.5.2")
(pods/load-pod [pod])
(require '[pod.kpassapk.emacs :as emacs])

;; cljbang-org needs org-ql, which is on MELPA.
(emacs/eval "(require 'package)
             (add-to-list 'package-archives '(\"melpa\" . \"https://melpa.org/packages/\") t)")

;; Install a package. This works even with emacs 29, which doesn't have :vc
(emacs/use-package! '(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org" :rev :newest)))

(emacs/clj!
 (require '[cljbang.org :as-alias org])

 (defn outline [file]
   {:file file
    :title (first (:title (org/keywords file)))
    :children (org/tree (org/headings file {:body? true}))}))

(def p (p/open))
(add-tap #'p/submit)

(tap> (emacs/clj! (outline ~org-file)))
