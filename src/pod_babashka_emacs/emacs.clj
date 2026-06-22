(ns pod-babashka-emacs.emacs
  "Resolve an Emacs binary for the host, downloading a portable build if the
  host has none.  This is what lets the pod work even without Emacs installed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn log [& xs]
  ;; pod stdout is the protocol channel; everything human-facing goes to stderr.
  (binding [*out* *err*] (apply println "[pod-babashka-emacs]" xs)))

(defn os-arch
  "Return [os arch] as normalized keywords, honoring babashka's pod override env."
  []
  (let [os   (or (System/getenv "BABASHKA_PODS_OS_NAME")
                 (System/getProperty "os.name"))
        arch (or (System/getenv "BABASHKA_PODS_OS_ARCH")
                 (System/getProperty "os.arch"))
        os*  (let [o (str/lower-case os)]
               (cond (str/includes? o "mac")     :macos
                     (str/includes? o "darwin")  :macos
                     (str/includes? o "win")     :windows
                     :else                       :linux))
        arch* (let [a (str/lower-case arch)]
                (cond (or (= a "aarch64") (= a "arm64")) :aarch64
                      (or (= a "x86_64") (= a "amd64"))  :x86_64
                      :else (keyword a)))]
    [os* arch*]))

(def ^:private download-sources
  "Portable Emacs builds per [os arch].  :url is a downloadable archive; :bin is
  the path to the Emacs executable inside the extracted archive.  Overridable via
  POD_BABASHKA_EMACS_URL.  These are app bundles / AppImages that ship their own
  lisp, so they run standalone."
  {[:macos :aarch64]
   {:url "https://github.com/jimeh/emacs-builds/releases/download/Emacs.app/Emacs-31-arm64.dmg"
    :kind :dmg
    :bin "Emacs.app/Contents/MacOS/Emacs"}
   [:macos :x86_64]
   {:url "https://github.com/jimeh/emacs-builds/releases/download/Emacs.app/Emacs-31-x86_64.dmg"
    :kind :dmg
    :bin "Emacs.app/Contents/MacOS/Emacs"}})

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
      (str/trim (slurp (fs/file marker))))))

(defn- extract-dmg!
  "Mount a .dmg, copy the bundle to dest-dir, detach.  macOS only."
  [dmg dest-dir bin-rel]
  (let [mount (str (fs/create-temp-dir {:prefix "pod-emacs-mnt"}))]
    (try
      (p/shell {:out :string :err :string}
               "hdiutil" "attach" "-nobrowse" "-mountpoint" mount (str dmg))
      (let [app (->> (fs/glob mount "*.app") first)]
        (when-not app (throw (ex-info "No .app in dmg" {:dmg dmg})))
        (fs/create-dirs dest-dir)
        (fs/copy-tree (str app) (str (fs/file dest-dir (fs/file-name app)))
                      {:replace-existing true}))
      (finally
        (p/shell {:out :string :err :string :continue true}
                 "hdiutil" "detach" mount)))
    (str (fs/file dest-dir bin-rel))))

(defn- download-emacs!
  "Download and extract a portable Emacs for [os arch]; return the binary path."
  [[os arch :as key]]
  (let [src (or (when-let [u (System/getenv "POD_BABASHKA_EMACS_URL")]
                  (assoc (get download-sources key
                              {:bin "Emacs.app/Contents/MacOS/Emacs" :kind :dmg})
                         :url u))
                (get download-sources key))]
    (when-not src
      (throw (ex-info (str "No portable Emacs source for " os "/" arch
                           ". Install Emacs, set POD_BABASHKA_EMACS_BIN, "
                           "or set POD_BABASHKA_EMACS_URL.")
                      {:os os :arch arch})))
    (log "no Emacs found; downloading portable build for" os arch "...")
    (let [dest    (fs/file (cache-dir) "emacs")
          archive (fs/file (cache-dir) (fs/file-name (:url src)))]
      (fs/create-dirs (cache-dir))
      (p/shell "curl" "-fSL" "-o" (str archive) (:url src))
      (let [bin (case (:kind src)
                  :dmg (extract-dmg! archive dest (:bin src))
                  (throw (ex-info "Unsupported archive kind" src)))]
        (fs/create-dirs (fs/file (cache-dir) "current"))
        (spit (fs/file (cache-dir) "current" "bin") bin)
        (log "installed Emacs at" bin)
        bin))))

(defn resolve-emacs
  "Return an absolute path to an Emacs executable, downloading one if needed.
  Order: $POD_BABASHKA_EMACS_BIN, cached download, system PATH, download."
  []
  (or (System/getenv "POD_BABASHKA_EMACS_BIN")
      (cached-emacs)
      (which-emacs)
      (download-emacs! (os-arch))))
