(ns release
  "Release helper for pod-kpassapk-emacs.

    bb release <version>            cut and publish a release (e.g. bb release 0.4.0)
    bb release <version> --dry-run  run every check, show planned edits, change nothing
    bb manifest                     print the pod-registry manifest.edn for the
                                    version currently in Cargo.toml

  The release flow:
    1. guards: on main, clean tree, tag free (local and origin), CHANGELOG has
       a non-empty [Unreleased] section
    2. bump the version in Cargo.toml
    3. cut CHANGELOG.md: [Unreleased] -> [<version>] - <today>, update the
       compare links at the bottom
    4. cargo build --release (refreshes Cargo.lock) and run the test suite
    5. commit \"Release v<version>\", tag, push main and the tag

  Pushing the tag triggers .github/workflows/release.yml, which builds the
  platform binaries and publishes the GitHub Release. Unlike pods that upload
  artifacts from the release machine (e.g. babashka-sql-pods with
  borkdude/gh-release-artifact), this pod needs a 4-platform build matrix, so
  CI owns the artifact uploads. See doc/release.md."
  (:require [babashka.process :refer [shell]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def repo "kpassapk/pod-kpassapk-emacs")
(def repo-url (str "https://github.com/" repo))
(def pod-exe "pod-kpassapk-emacs")

(def root
  (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))

(defn die [& msg]
  (binding [*out* *err*] (println (apply str "release: " msg)))
  (System/exit 1))

(defn sh-out [& args]
  (-> (apply shell {:out :string :dir root} args) :out str/trim))

(defn cargo-version []
  (or (second (re-find #"(?m)^version = \"(\d+\.\d+\.\d+)\""
                       (slurp (io/file root "Cargo.toml"))))
      (die "cannot read version from Cargo.toml")))

;;;; ------------------------------------------------------------- manifest

(defn manifest [version]
  (let [dl (fn [platform]
             (str repo-url "/releases/download/v" version
                  "/" pod-exe "-" version "-" platform ".zip"))
        art (fn [os arch platform]
              (str "  {:os/name \"" os "\"\n"
                   "   :os/arch \"" arch "\"\n"
                   "   :artifact/url \"" (dl platform) "\"\n"
                   "   :artifact/executable \"" pod-exe "\"}"))]
    (str "{:pod/name kpassapk/emacs\n"
         " :pod/description \"Expose Emacs (org-mode, calc, project.el, org-roam, ...) to babashka scripts\"\n"
         " :pod/version \"" version "\"\n"
         " :pod/license \"EPL-1.0\"\n"
         " :pod/language \"rust\"\n"
         " :pod/artifacts\n"
         " [" (str/trim (art "Linux.*" "amd64" "linux-amd64")) "\n"
         (art "Linux.*" "aarch64" "linux-aarch64") "\n"
         (art "Mac.*" "x86_64" "macos-amd64") "\n"
         (art "Mac.*" "aarch64" "macos-aarch64") "]}\n")))

(defn print-manifest [& _]
  (print (manifest (cargo-version)))
  (flush))

;;;; ------------------------------------------------------------- changelog

(defn cut-changelog
  "Return CHANGELOG text with [Unreleased] cut to [version] - today."
  [text version prev today]
  (let [unreleased-header "## [Unreleased]"
        section (second (re-find #"(?s)## \[Unreleased\]\n(.*?)\n## \[" text))]
    (when-not (str/includes? text unreleased-header)
      (die "CHANGELOG.md has no ## [Unreleased] section"))
    (when (or (nil? section) (str/blank? section))
      (die "the [Unreleased] section is empty — nothing to release"))
    (-> text
        (str/replace-first
         unreleased-header
         (str unreleased-header "\n\n## [" version "] - " today))
        (str/replace-first
         (str "[Unreleased]: " repo-url "/compare/v" prev "...HEAD")
         (str "[Unreleased]: " repo-url "/compare/v" version "...HEAD\n"
              "[" version "]: " repo-url "/compare/v" prev "...v" version)))))

;;;; ------------------------------------------------------------- release

(defn guard! [version prev]
  (when-not (re-matches #"\d+\.\d+\.\d+" version)
    (die "version must look like X.Y.Z, got: " version))
  (when (= version prev)
    (die "version " version " is already the version in Cargo.toml"))
  (let [branch (sh-out "git rev-parse --abbrev-ref HEAD")]
    (when-not (= "main" branch)
      (die "must release from main, currently on: " branch)))
  (when-not (str/blank? (sh-out "git status --porcelain"))
    (die "working tree is not clean — commit or stash first"))
  (when-not (str/blank? (sh-out "git tag -l" (str "v" version)))
    (die "tag v" version " already exists locally"))
  (when-not (str/blank? (sh-out "git ls-remote --tags origin" (str "v" version)))
    (die "tag v" version " already exists on origin")))

(defn release [version dry-run?]
  (let [prev (cargo-version)
        _ (guard! version prev)
        today (str (java.time.LocalDate/now))
        cargo-file (io/file root "Cargo.toml")
        changelog-file (io/file root "CHANGELOG.md")
        cargo' (str/replace-first (slurp cargo-file)
                                  (str "version = \"" prev "\"")
                                  (str "version = \"" version "\""))
        changelog' (cut-changelog (slurp changelog-file) version prev today)]
    (println (str "release: v" prev " -> v" version " (" today ")"))
    (if dry-run?
      (println "release: dry run — all checks passed; no files changed")
      (do
        (spit cargo-file cargo')
        (spit changelog-file changelog')
        (println "release: building and testing...")
        (shell {:dir root} "cargo build --release")
        (shell {:dir root} "bb scripts/run-tests.clj")
        (shell {:dir root} "git add Cargo.toml Cargo.lock CHANGELOG.md")
        (shell {:dir root} "git commit -m" (str "Release v" version))
        (shell {:dir root} "git tag" (str "v" version))
        (shell {:dir root} "git push origin main" (str "v" version))
        (println)
        (println (str "release: v" version " pushed — CI is building the binaries."))
        (println "release: watch it with:   gh run watch")
        (println (str "release: then verify:     gh release view v" version))
        (println)
        (println "pod-registry manifest for this release (see doc/release.md):")
        (println)
        (println (manifest version))))))

(defn main [& args]
  (let [[cmd & rest-args] args]
    (cond
      (= cmd "manifest") (print-manifest)
      (nil? cmd) (die "usage: bb release <version> [--dry-run]  |  bb manifest")
      :else (release cmd (boolean (some #{"--dry-run"} rest-args))))))

;; Allow running directly too: bb scripts/release.clj <version>
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
