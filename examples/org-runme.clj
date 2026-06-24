#!/usr/bin/env bb
;; Something like runme.dev but with org mode
;;
;;   bb examples/org-runme.clj [path/to/runbook.org]
;;
;; Blocks are run *off the UI thread* by wrapping the ordinary synchronous
;; `org/execute' var in a `future'.  Pod invokes are id-routed, so the call
;; doesn't block the reader; we poll the future and animate a spinner while a
;; slow step (say `sleep 8`) runs, instead of freezing the whole UI.

(require '[babashka.pods :as pods]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.utils NonBlockingReader])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "pod-babashka-emacs")))
(def org-file (or (first *command-line-args*)
                  (.getPath (io/file here "runbook.org"))))

(pods/load-pod [pod])
(require '[pod.babashka.emacs.org :as org])

(defn block-label [b]
  (or (:name b) (format "«block %d»" (:index b))))

(defn block-selector
  "How org/execute should address this block: by :name if it has one, else :index."
  [b]
  (if (:name b) {:name (:name b)} {:index (:index b)}))

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
       "Pick a src block to run.  ↑/↓ or j/k to move, Enter to run, q to quit.\r\n\r\n"
       (str/join "\r\n"
                 (map-indexed
                  (fn [i b]
                    (let [row (format "%-16s [%s]" (block-label b) (:lang b))]
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
  `org/execute' is synchronous, so we wrap it in a `future' and poll: the pod
  reply is id-routed, so the call returns without blocking the UI thread and we
  can animate a spinner while Emacs works.  Errors come back tagged, not thrown."
  [terminal b]
  (let [w      (.writer terminal)
        label  (block-label b)
        result (future (try [:ok (org/execute org-file (block-selector b))]
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
                         "\r\n\r\nPress any key to return …"))
          (.flush w)
          (read-key (.reader terminal)))))))

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
        (\q :quit) :done
        (recur idx)))))

;;;; --------------------------------------------------- dumb-terminal fallback

(defn fallback
  "No real TTY (piped/redirected): a plain numbered prompt instead of the TUI."
  [blocks]
  (println "Runbook:" org-file)
  (doseq [[i b] (map-indexed vector blocks)]
    (println (format "  %d) %-16s [%s]" i (block-label b) (:lang b))))
  (print "Select block number (q to quit): ") (flush)
  (let [in (str/trim (or (read-line) ""))]
    (when-not (#{"q" ""} in)
      (if-let [b (get blocks (parse-long in))]
        (println "=>" (pr-str (org/execute org-file (block-selector b))))
        (println "No such block:" in)))))

;;;; ---------------------------------------------------------------------- main

(let [blocks   (vec (org/src-blocks org-file))
      terminal (-> (TerminalBuilder/builder) (.system true) (.build))]
  (cond
    (empty? blocks)               (println "No src blocks in" org-file)
    (= "dumb" (.getType terminal)) (do (fallback blocks) (.close terminal))
    :else
    (try
      (tui terminal blocks)
      (finally
        (doto (.writer terminal) (.print (str (show-cursor) "\r\n")) (.flush))
        (.close terminal)))))   ; close restores the original terminal attributes
