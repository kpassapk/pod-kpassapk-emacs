(ns vendor
  "Vendored-elisp updater for pod-kpassapk-emacs.

    bb vendor            update every vendored library to upstream HEAD
    bb vendor <lib> ...  update only the named libraries (see `libs`)

  For each library this shallow-clones the upstream repo, copies the
  vendored files into vendor/, and rewrites the pin table in
  vendor/README.md with the new revision. The files are embedded into
  the pod binary (src/elisp.rs), so run `bb test` after updating."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Every library here must be under a licence compatible with the pod's own
;; EPL-1.0, because the files are embedded verbatim in the released binary and
;; the registry ships that binary.  GPL is not: parseedn, parseclj and a.el
;; were dropped for that reason, cljbang's reader and printer standing in for
;; parseedn.  Weigh the licence before adding a library, not after.
(def libs
  "lib name -> upstream repo, licence, copyright, and the files vendored."
  (array-map
   "cljbang"  {:repo "https://github.com/borkdude/cljbang.el"
               :license "MIT"
               :copyright "Copyright (c) 2026 Michiel Borkent"
               :files ["cljbang.el" "cljbang-core.el" "cljbang-string.el"]}
   "bencode"  {:repo "https://github.com/skeeto/emacs-bencode"
               :license "Unlicense"
               :copyright "Christopher Wellons <wellons@nullprogram.com>"
               :files ["bencode.el"]}))

(def license-texts
  "Licence name -> the text NOTICE has to reproduce for it."
  {"MIT"
   (str "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
        "of this software and associated documentation files (the \"Software\"), to deal\n"
        "in the Software without restriction, including without limitation the rights\n"
        "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
        "copies of the Software, and to permit persons to whom the Software is\n"
        "furnished to do so, subject to the following conditions:\n"
        "\n"
        "The above copyright notice and this permission notice shall be included in all\n"
        "copies or substantial portions of the Software.\n"
        "\n"
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
        "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
        "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
        "SOFTWARE.\n")
   "Unlicense"
   (str "This is free and unencumbered software released into the public domain.\n"
        "\n"
        "Anyone is free to copy, modify, publish, use, compile, sell, or distribute\n"
        "this software, either in source code form or as a compiled binary, for any\n"
        "purpose, commercial or non-commercial, and by any means.\n"
        "\n"
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
        "AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN\n"
        "ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION\n"
        "WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.\n"
        "\n"
        "For more information, please refer to <https://unlicense.org>\n")})

(def root
  (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))

(def vendor-dir (io/file root "vendor"))
(def readme-file (io/file vendor-dir "README.md"))
(def notice-file (io/file root "NOTICE"))

(defn die [& msg]
  (binding [*out* *err*] (println (apply str "vendor: " msg)))
  (System/exit 1))

(defn sh-out [& args]
  (-> (apply shell {:out :string} args) :out str/trim))

(defn short [sha] (subs sha 0 12))

(defn current-pin
  "The sha pinned in the README for this repo url, or nil."
  [readme-text repo]
  (some (fn [line]
          (when (str/includes? line repo)
            (second (re-find #"`([0-9a-f]{7,40})`" line))))
        (str/split-lines readme-text)))

(defn update!
  "Clone repo, copy its files into vendor/ unless already at the pinned
  sha. Return the sha the library is now pinned at."
  [name {:keys [repo files]} pinned]
  (fs/with-temp-dir [tmp {:prefix "vendor"}]
    (shell "git clone --quiet --depth 1" repo (str tmp))
    (let [sha (sh-out "git" "-C" (str tmp) "rev-parse" "HEAD")]
      (if (= sha pinned)
        (do (println (str "vendor: " name " already at " (short sha)))
            pinned)
        (do (doseq [f files]
              (let [src (fs/file (str tmp) f)]
                (when-not (fs/exists? src)
                  (die f " not found in " repo " @ " (short sha)
                       " — did upstream move it?"))
                (fs/copy src (fs/file vendor-dir f) {:replace-existing true})))
            (println (str "vendor: " name " "
                          (if pinned (str (short pinned) " -> ") "pinned at ")
                          (short sha)))
            sha)))))

(defn readme-text
  "Regenerate vendor/README.md from `libs` and the pins map (name -> sha)."
  [pins]
  (let [row (fn [[name {:keys [repo license files]}]]
              (str "| " (str/join ", " (map #(str "`" % "`") files))
                   " | " repo
                   " | " (if-let [sha (pins name)] (str "`" sha "`") "—")
                   " (" license ") |"))]
    (str "# Vendored elisp\n"
         "\n"
         "Third-party libraries the emacs child loads (`-L vendor`), copied in verbatim\n"
         "and embedded into the pod binary (see `src/elisp.rs`).\n"
         "\n"
         "| Library | Upstream | Pinned |\n"
         "|---|---|---|\n"
         (str/join "\n" (map row libs))
         "\n"
         "\n"
         "To bump: `bb vendor` (all) or `bb vendor <lib> ...` where lib is one of\n"
         (str/join ", " (keys libs)) ". Then run `bb test`.\n"
         "This table is generated by scripts/vendor.clj — edit `libs` there, not here.\n"
         "`cljbang-mode.el` is intentionally not vendored — it is interactive-only.\n"
         "\n"
         "A library goes in here only if its licence is compatible with the pod's\n"
         "EPL-1.0: the released binary carries these files, so their terms travel\n"
         "with it. See the note above `libs` in scripts/vendor.clj.\n")))

(defn notice-text
  "Regenerate NOTICE: the attribution the vendored licences require."
  []
  (let [section (fn [[name {:keys [repo license copyright]}]]
                  (str "-------------------------------------------------------------------\n"
                       name " (" repo ")\n"
                       "Licensed under the " license " licence.\n"
                       "\n"
                       copyright "\n"
                       "\n"
                       (license-texts license)))]
    (str "pod-kpassapk-emacs\n"
         "Copyright © 2026 Kyle Passarelli\n"
         "Distributed under the Eclipse Public License 1.0 — see LICENSE.\n"
         "\n"
         "The released binary embeds the third-party elisp under vendor/ verbatim.\n"
         "Those libraries keep their own licences, reproduced below.\n"
         "\n"
         (str/join "\n" (map section libs))
         "\n"
         "This file is generated by scripts/vendor.clj — edit `libs` there, not here.\n")))

(defn main [& args]
  (let [names (or (seq args) (keys libs))]
    (doseq [n names]
      (when-not (contains? libs n)
        (die "unknown library: " n " (known: " (str/join ", " (keys libs)) ")")))
    (let [text (slurp readme-file)
          pins (into {} (for [[n {:keys [repo]}] libs]
                          [n (current-pin text repo)]))
          pins' (reduce (fn [acc n] (assoc acc n (update! n (libs n) (acc n))))
                        pins names)]
      (spit readme-file (readme-text pins'))
      (spit notice-file (notice-text))
      (println "vendor: wrote vendor/README.md and NOTICE — now run: bb test"))))

;; Allow running directly too: bb scripts/vendor.clj [lib ...]
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
