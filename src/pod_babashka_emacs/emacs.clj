(ns pod-babashka-emacs.emacs
  "Resolve an Emacs binary."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn log [& xs]
  ;; pod stdout is the protocol channel; everything human-facing goes to stderr.
  (binding [*out* *err*] (apply println "[pod-babashka-emacs]" xs)))

(defn cache-dir []
  (let [base (or (System/getenv "POD_BABASHKA_EMACS_CACHE")
                 (some-> (System/getenv "XDG_CACHE_HOME")
                         (str "/pod-babashka-emacs"))
                 (str (System/getProperty "user.home")
                      "/.cache/pod-babashka-emacs"))]
    (fs/file base)))

(defn- which-emacs []
  (when-let [hit (or (fs/which "emacs")
                     (let [app "/Applications/Emacs.app/Contents/MacOS/Emacs"]
                       (when (fs/executable? app) app)))]
    (str hit)))

(defn- cached-emacs []
  (let [marker (fs/file (cache-dir) "current" "bin")]
    (when (fs/exists? marker)
      (let [bin (str/trim (slurp (fs/file marker)))]
        (when (fs/executable? bin) bin)))))

(defn resolve-emacs []
  (or (System/getenv "POD_BABASHKA_EMACS_BIN")
      (cached-emacs)
      (which-emacs)))
