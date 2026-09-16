(ns release
  "Release helper for pod-kpassapk-emacs.

    bb release <version>            cut and publish a release (e.g. bb release 0.5.2)
    bb release <version> --dry-run  run every check, show planned edits, change nothing
    bb registry-pr [version]        open the pod-registry PR for a published release
                                    (default: the version in Cargo.toml; --dry-run
                                    commits in a temporary clone and stops there)
    bb manifest                     print the pod-registry manifest.edn for the
                                    version currently in Cargo.toml

  The release flow:
    1. guards: on main, clean tree, tag free (local and origin), CHANGELOG has
       a non-empty [Unreleased] section, gh can reach the pod-registry fork
    2. bump the version in Cargo.toml
    3. cut CHANGELOG.md: [Unreleased] -> [<version>] - <today>, update the
       compare links at the bottom
    4. bb test: cargo build --release (refreshes Cargo.lock), then the test
       suite in a temporary user dir
    5. commit \"Release v<version>\", tag, push main and the tag
    6. registry PR: wait for CI to publish the GitHub Release, then open the
       babashka/pod-registry PR from the fork

  Pushing the tag triggers .github/workflows/release.yml, which builds the
  platform binaries and publishes the GitHub Release. Unlike pods that upload
  artifacts from the release machine (e.g. babashka-sql-pods with
  borkdude/gh-release-artifact), this pod needs a 4-platform build matrix, so
  CI owns the artifact uploads and the registry PR waits for them. See
  doc/release.md."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def repo "kpassapk/pod-kpassapk-emacs")
(def repo-url (str "https://github.com/" repo))
(def pod-exe "pod-kpassapk-emacs")
(def pod-name "kpassapk/emacs")

(def registry "babashka/pod-registry")
(def registry-branch "master")
(def registry-fork "kpassapk/pod-registry")
(def registry-example "examples/kpassapk_emacs.clj")

(def platforms
  "The zips release.yml builds, with the os.name/os.arch patterns the registry
  matches them against."
  [{:os "Linux.*" :arch "amd64"   :artifact "linux-amd64"}
   {:os "Linux.*" :arch "aarch64" :artifact "linux-aarch64"}
   {:os "Mac.*"   :arch "x86_64"  :artifact "macos-amd64"}
   {:os "Mac.*"   :arch "aarch64" :artifact "macos-aarch64"}])

(def root
  (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))

(defn die [& msg]
  (binding [*out* *err*] (println (apply str "release: " msg)))
  (System/exit 1))

(defn sh-out [& args]
  (-> (apply shell {:out :string :dir root} args) :out str/trim))

(defn succeeds?
  "Run a command quietly; true if it exits 0."
  [& args]
  (zero? (:exit (apply shell {:out :string :err :string :continue true :dir root}
                       args))))

(defn cargo-version []
  (or (second (re-find #"(?m)^version = \"(\d+\.\d+\.\d+)\""
                       (slurp (io/file root "Cargo.toml"))))
      (die "cannot read version from Cargo.toml")))

(defn check-version! [version]
  (when-not (re-matches #"\d+\.\d+\.\d+" version)
    (die "version must look like X.Y.Z, got: " version)))

;;;; ------------------------------------------------------------- manifest

(defn zip-name [version {:keys [artifact]}]
  (str pod-exe "-" version "-" artifact ".zip"))

(defn manifest [version]
  (let [art (fn [platform]
              (str "{:os/name \"" (:os platform) "\"\n"
                   "   :os/arch \"" (:arch platform) "\"\n"
                   "   :artifact/url \"" repo-url "/releases/download/v" version
                   "/" (zip-name version platform) "\"\n"
                   "   :artifact/executable \"" pod-exe "\"}"))]
    (str "{:pod/name " pod-name "\n"
         " :pod/description \"Expose Emacs to babashka scripts: run Clojure inside Emacs with clj!\"\n"
         " :pod/version \"" version "\"\n"
         " :pod/license \"EPL-1.0\"\n"
         " :pod/language \"rust\"\n"
         " :pod/artifacts\n"
         " [" (str/join "\n  " (map art platforms)) "]}\n")))

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

;;;; ------------------------------------------------------------- registry

(defn wait-for-release!
  "Block until release.yml has published v<version>'s GitHub Release with every
  platform zip. Dies if the run fails or a zip is missing."
  [version]
  (let [tag (str "v" version)]
    (println (str "release: waiting for the release.yml run for " tag "..."))
    ;; Right after the tag push the run can take a few seconds to show up.
    (let [run-id (loop [tries 24]
                   (let [id (sh-out "gh run list --workflow release.yml --limit 1 --json databaseId --jq .[].databaseId -R"
                                    repo "--branch" tag)]
                     (cond (not (str/blank? id)) id
                           (zero? tries) (die "no release.yml run for " tag " after 2 minutes")
                           :else (do (Thread/sleep 5000) (recur (dec tries))))))]
      (shell {:dir root} "gh run watch --exit-status -R" repo run-id))
    (let [assets (set (str/split-lines
                       (sh-out "gh release view --json assets --jq .assets[].name -R" repo tag)))
          missing (remove assets (map #(zip-name version %) platforms))]
      (when (seq missing)
        (die "GitHub Release " tag " is missing " (str/join ", " missing))))))

(defn bump!
  "Replace the first match of re in dir/path with (f match). Throws rather than
  dies, so the caller's finally still removes the clone."
  [dir path re f]
  (let [file (io/file dir path)
        text (slurp file)
        text' (str/replace-first text re f)]
    (when (= text text')
      (throw (ex-info (str "pod-registry: found no " pod-name " version to bump in " path)
                      {:path path})))
    (spit file text')))

(defn registry-pr!
  "Open the babashka/pod-registry PR for v<version> from a temporary clone of
  the fork: add the manifest, and bump the version in the README's pod table and
  in the example, as each registry PR has. With dry-run?, stop after committing
  in the clone and show the commit."
  [version dry-run?]
  (let [branch (str "kpassapk-emacs-" version)
        title (str "Update " pod-name " pod to " version)
        manifest-path (str "manifests/" pod-name "/" version "/manifest.edn")
        open-pr (sh-out "gh pr list --state open --json url --jq .[].url -R"
                        registry "--head" branch)
        quoted-name (java.util.regex.Pattern/quote pod-name)]
    (when-not (str/blank? open-pr)
      (die "a pod-registry PR for " version " is already open: " open-pr))
    (when (succeeds? "gh api" (str "repos/" registry "/contents/" manifest-path))
      (die registry " already has " manifest-path))
    (wait-for-release! version)
    (let [tmp (fs/create-temp-dir {:prefix "pod-registry"})
          dir (str (fs/path tmp "pod-registry"))
          git (fn [& args] (apply shell {:dir dir} args))]
      (try
        ;; gh adds the fork's parent as the `upstream` remote, so the branch
        ;; starts from the registry itself however far behind the fork is.
        (shell {:dir root} "gh repo clone" registry-fork dir "--" "--quiet")
        (git "git checkout --quiet --no-track -b" branch (str "upstream/" registry-branch))
        (fs/create-dirs (fs/parent (fs/path dir manifest-path)))
        (spit (io/file dir manifest-path) (manifest version))
        ;; | [kpassapk/emacs](<url>) | <description> | <version> | ...
        (bump! dir "README.md"
               (re-pattern (str "(?m)^(\\| \\[" quoted-name "\\]\\([^)]*\\) \\|[^|]*\\| )[^|\\s]+"))
               (fn [[_ row-start]] (str row-start version)))
        ;; (pods/load-pod 'kpassapk/emacs "<version>")
        (bump! dir registry-example
               (re-pattern (str "(\\(pods/load-pod '" quoted-name " \")[^\"]+"))
               (fn [[_ call-start]] (str call-start version)))
        (git "git add --all")
        (git "git commit --quiet -m" title)
        (if dry-run?
          (do (git "git --no-pager show --stat --patch HEAD")
              (println "release: dry run — branch not pushed, PR not opened"))
          (let [;; Forced so a rerun can replace a branch left by a run that
                ;; pushed but failed to open the PR; an open PR on it was
                ;; ruled out above.
                _ (git "git push --quiet --force origin" branch)
                fork-owner (first (str/split registry-fork #"/"))
                body (str "Release: " repo-url "/releases/tag/v" version "\n"
                          "Changelog: " repo-url "/blob/v" version "/CHANGELOG.md")
                url (-> (shell {:dir dir :out :string}
                               "gh pr create -R" registry "--base" registry-branch
                               "--head" (str fork-owner ":" branch)
                               "--title" title "--body" body)
                        :out str/trim)]
            (println (str "release: opened " url))))
        (finally (fs/delete-tree tmp))))))

(defn registry-pr-main [& args]
  (let [dry-run? (boolean (some #{"--dry-run"} args))
        version (or (first (remove #{"--dry-run"} args)) (cargo-version))]
    (check-version! version)
    (registry-pr! version dry-run?)))

;;;; ------------------------------------------------------------- release

(defn guard! [version prev]
  (check-version! version)
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
    (die "tag v" version " already exists on origin"))
  ;; The release ends by opening the registry PR; find out now, not after the
  ;; tag is pushed, if it can't.
  (when-not (succeeds? "gh repo view --json name" registry-fork)
    (die "gh cannot reach " registry-fork ", needed for the pod-registry PR (gh auth status?)")))

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
        ;; `bb test', not the runner directly: the task builds first and gives
        ;; the suite a fresh user dir, so packages already in <cache>/emacs.d
        ;; cannot fail the release.
        (shell {:dir root} "bb test")
        (shell {:dir root} "git add Cargo.toml Cargo.lock CHANGELOG.md")
        (shell {:dir root} "git commit -m" (str "Release v" version))
        (shell {:dir root} "git tag" (str "v" version))
        (shell {:dir root} "git push origin main" (str "v" version))
        (println)
        (println (str "release: v" version " pushed — CI is building the binaries."))
        (println (str "release: if the pod-registry step fails, rerun it with: bb registry-pr " version))
        (registry-pr! version false)))))

(defn main [& args]
  (let [[cmd & rest-args] args]
    (cond
      (= cmd "manifest") (print-manifest)
      (= cmd "registry-pr") (apply registry-pr-main rest-args)
      (nil? cmd) (die "usage: bb release <version> [--dry-run]  |  bb registry-pr [version] [--dry-run]  |  bb manifest")
      :else (release cmd (boolean (some #{"--dry-run"} rest-args))))))

;; Allow running directly too: bb scripts/release.clj <version>
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
