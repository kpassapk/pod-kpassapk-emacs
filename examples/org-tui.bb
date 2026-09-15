#!/usr/bin/env bb
;; Something like runme.dev but with org mode
;;
;;   bb examples/org-tui.bb [path/to/runbook.org]
;;
;; Blocks are run *off the UI thread* by wrapping the ordinary synchronous
;; `org/execute!' call in a `future'.  Pod invokes are id-routed, so the call
;; doesn't block the reader; we poll the future and animate a spinner while a
;; slow step (say `sleep 8`) runs, instead of freezing the whole UI.
;;
;; The org file is read and run by cljbang-org, an ordinary Emacs package
;; called through `emacs/clj!' — no pod namespace in front of it.  A run leaves
;; its results in Emacs' buffer and nothing on disk until you press `s'.

(require '[babashka.pods :as pods]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.utils NonBlockingReader])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "target" "release" "pod-kpassapk-emacs")))
(def org-file (or (first *command-line-args*)
                  (.getPath (io/file here "runbook.org"))))

(pods/load-pod [pod])
(require '[pod.kpassapk.emacs :as emacs])

;; cljbang-org needs org-ql, which is on MELPA.  The pod's Emacs starts with
;; only GNU and NonGNU ELPA, so add MELPA before installing.
(emacs/eval "(require 'package)
             (add-to-list 'package-archives '(\"melpa\" . \"https://melpa.org/packages/\") t)")

;; Install cljbang-org into the batch Emacs on first run, and load it.  The
;; argument is an ordinary `use-package' declaration — `:vc' is what fetches —
;; so any package use-package can reach is reachable without rebuilding the pod.
;; `:rev :newest' takes the default branch: package-vc otherwise checks out the
;; commit that last bumped `Version:', and `call-blocks' and `execute!' are
;; newer than that.
(emacs/use-package! '(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org" :rev :newest)))

;; Defined once inside Emacs; definitions persist for the pod session.
;; A src block and a `#+call:' line are both steps a runbook can run, and they
;; share one :index, so merging the two readers gives the file's steps in order.
(emacs/clj!
 (require '[cljbang.org :as-alias org])

 (defn runnable [file]
   (->> (concat (org/src-blocks file) (org/call-blocks file))
        (sort-by :index)
        vec)))

(defn block-label
  "A block's `#+name:', else a call line verbatim — `exists(path=\"bb.edn\")'
  says more than the name of the block it invokes, which a src block row
  above is already using."
  [b]
  (or (:name b) (:value b) (format "«block %d»" (:index b))))

(defn block-kind
  "What to show in the type column; a call line has no language of its own."
  [b]
  (or (:language b) "call"))

(defn selector
  "A block map is already a selector — its :name wins, its :index is the
  fallback — so this only trims it to the two keys that need to travel."
  [b]
  (select-keys b [:name :index]))

;;;; ---------------------------------------------------------- terminal drawing

(def ESC "")
(defn- clear       [] (str ESC "[2J" ESC "[H"))
(defn- hide-cursor [] (str ESC "[?25l"))
(defn- show-cursor [] (str ESC "[?25h"))

(defn render
  "Build the full-screen menu as a string (raw mode -> explicit CR+LF)."
  [blocks idx]
  (str (clear) (hide-cursor)
       "Runbook: " org-file "\r\n"
       "Pick a step to run.  ↑/↓ or j/k to move, Enter to run, s to save, q to quit.\r\n\r\n"
       (str/join "\r\n"
                 (map-indexed
                  (fn [i b]
                    (let [row (format "%-16s [%s]" (block-label b) (block-kind b))]
                      (if (= i idx)
                        (str ESC "[7m> " row ESC "[0m")   ; reverse-video cursor row
                        (str "  " row))))
                  blocks))
       "\r\n"))

(defn read-key
  "Block for one keystroke; decode arrow escapes and control keys to keywords."
  [^NonBlockingReader r]
  (let [c (.read r)]
    (cond
      (= c 27) (let [c2 (.read r 50)]                ; ESC: maybe an arrow sequence
                 (if (= c2 (int \[))
                   (case (char (.read r 50))
                     \A :up \B :down \C :right \D :left :other)
                   :esc))
      (or (= c 13) (= c 10)) :enter
      (or (= c 3) (= c 4))   :quit                   ; Ctrl-C / Ctrl-D
      (neg? c)               :quit                   ; EOF
      :else (char c))))

;;;; ------------------------------------------------------------------- running

(def spinner ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn run-block!
  "Run B through the pod on a background thread and show the result.
  `org/execute!' is synchronous, so we wrap it in a `future' and poll: the pod
  reply is id-routed, so the call returns without blocking the UI thread and we
  can animate a spinner while Emacs works.  Errors come back tagged, not thrown
  — including a block that exits non-zero, which carries its exit code."
  [terminal b]
  (let [w      (.writer terminal)
        label  (block-label b)
        sel    (selector b)
        result (future (try [:ok (emacs/clj! (cljbang.org/execute! ~org-file ~sel))]
                            (catch Exception e [:err (ex-message e)])))]
    (loop [i 0]
      (if-not (realized? result)
        (do (.print w (str (clear) (hide-cursor)
                           "Running " label " " (nth spinner (mod i (count spinner)))
                           "\r\n\r\n(Emacs is working — the block is still running)"))
            (.flush w)
            (Thread/sleep 120)                            ; ~8 fps
            (recur (inc i)))
        (let [[status val] @result]
          (.print w (str (clear) (show-cursor)
                         "Ran " label "\r\n\r\n"
                         (if (= status :ok)
                           (str "=> " (pr-str val))
                           (str "ERROR: " val))
                         "\r\n\r\n(the #+RESULTS: are in Emacs' buffer; s saves them)"
                         "\r\n\r\nPress any key to return …"))
          (.flush w)
          (read-key (.reader terminal)))))))

(defn save!
  "Write what the runs left in Emacs' buffer out to the org file."
  [terminal]
  (let [w (.writer terminal)]
    (.print w (str (clear) (show-cursor)
                   "Saved " (emacs/clj! (cljbang.org/save! ~org-file))
                   "\r\n\r\nPress any key to return …"))
    (.flush w)
    (read-key (.reader terminal))))

(defn tui [terminal blocks]
  (let [reader (.reader terminal)
        w      (.writer terminal)
        n      (count blocks)]
    (.enterRawMode terminal)
    (loop [idx 0]
      (.print w (render blocks idx)) (.flush w)
      (case (read-key reader)
        (:up   \k) (recur (mod (dec idx) n))
        (:down \j) (recur (mod (inc idx) n))
        :enter     (do (run-block! terminal (nth blocks idx)) (recur idx))
        \s         (do (save! terminal) (recur idx))
        (\q :quit) :done
        (recur idx)))))

;;;; --------------------------------------------------- dumb-terminal fallback

(defn fallback
  "No real TTY (piped/redirected): a plain numbered prompt instead of the TUI."
  [blocks]
  (println "Runbook:" org-file)
  (doseq [[i b] (map-indexed vector blocks)]
    (println (format "  %d) %-16s [%s]" i (block-label b) (block-kind b))))
  (print "Select block number (q to quit): ") (flush)
  (let [in (str/trim (or (read-line) ""))]
    (when-not (#{"q" ""} in)
      (if-let [b (get blocks (parse-long in))]
        (let [sel (selector b)]
          (println "=>" (pr-str (emacs/clj! (cljbang.org/execute! ~org-file ~sel))))
          (println "(the #+RESULTS: are in Emacs' buffer, not on disk)"))
        (println "No such block:" in)))))

;;;; ---------------------------------------------------------------------- main

(let [blocks   (emacs/clj! (runnable ~org-file))
      terminal (-> (TerminalBuilder/builder) (.system true) (.build))]
  (cond
    (empty? blocks)               (println "No runnable blocks in" org-file)
    (= "dumb" (.getType terminal)) (do (fallback blocks) (.close terminal))
    :else
    (try
      (tui terminal blocks)
      (finally
        (doto (.writer terminal) (.print (str (show-cursor) "\r\n")) (.flush))
        (.close terminal)))))   ; close restores the original terminal attributes
