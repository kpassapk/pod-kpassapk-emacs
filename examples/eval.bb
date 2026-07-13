#!/usr/bin/env bb

(require '[babashka.pods :as pods]
         '[clojure.java.io :as io])

;; Loading emacs packages is deferred, so startup time is fast.
;;
;; $ time bb eval.bb
;; [pod-kpassapk-emacs] emacs: /opt/homebrew/bin/emacs
;; 2
;; 
;; ________________________________________________________
;; Executed in  241.18 millis    fish           external
;;    usr time  270.08 millis    0.28 millis  269.80 millis
;;    sys time   92.17 millis    1.78 millis   90.38 millis
;;
;; time emacs --batch -Q --eval "(+ 1 1)"
;; 
;; ________________________________________________________
;; Executed in  188.19 millis    fish           external
;;    usr time  235.49 millis    0.27 millis  235.22 millis
;;    sys time   78.36 millis    1.72 millis   76.64 millis

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "target" "release" "pod-kpassapk-emacs")))

(pods/load-pod [pod])
(require '[pod.babashka.emacs :as emacs])

(prn (emacs/eval "(+ 1 1)"))
