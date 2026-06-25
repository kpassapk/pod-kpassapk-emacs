#!/usr/bin/env bb

(require '[babashka.deps :as deps]
         '[babashka.pods :as pods]
         '[clojure.java.io :as io])

(deps/add-deps '{:deps {djblue/portal {:mvn/version "0.65.0"}}})

(require '[portal.api :as p])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "pod-babashka-emacs")))
(def org-file (or (first *command-line-args*)
                  (.getPath (io/file here "sample.org"))))

(pods/load-pod [pod])
(require '[pod.babashka.emacs.org :as org])

(def p (p/open))
(add-tap #'p/submit)

(tap> (org/outline org-file))
