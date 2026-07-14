#!/usr/bin/env bb
;; devops runbook TUI: pick a src block and run it on its heading's target.
;;
;;   bb examples/devops.bb [path/to/devops.org]
;;
;; Emacs supplies the runbook's structure -- devops/src-blocks gives every
;; block its :body, :heading and target tags, devops/targets gives each tag a
;; full connection spec -- and babashka does the running: remote targets go
;; over ssh via clojuressh, one session per target tag, opened the first time
;; a block runs there and kept open for reuse.  Tangling (`t') stays in
;; Emacs, where org-babel-tangle already writes through TRAMP.

(require '[babashka.deps :as deps]
         '[babashka.pods :as pods]
         '[babashka.process :as process]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.utils NonBlockingReader])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "target" "release" "pod-kpassapk-emacs")))
(def org-file (or (first *command-line-args*)
                  (.getPath (io/file here "devops.org"))))
(def org-dir (-> org-file io/file .getCanonicalFile .getParent))

(deps/add-deps '{:deps {io.epiccastle/clojuressh {:mvn/version "1.0.0"}}})

(pods/load-pod [pod])

(require '[clojuressh.core :as ssh]
         '[clojuressh.channel-exec :as channel-exec]
         '[clojuressh.session :as session])

(require '[pod.kpassapk.emacs.devops :as devops])

;;;; -------------------------------------------------------------- running

(def shell-langs #{"sh" "shell" "bash"})

(def sessions
  "tag -> live clojuressh session.  The first command on a target pays the
  connection cost; every later command on the same tag reuses the session."
  (atom {}))

(defn ssh-config
  "Resolve HOST through `ssh -G`, returning {:hostname :user :port}.
  bbssh doesn't read ~/.ssh/config, so Host aliases (Tailscale machine
  names, jump shorthands) would otherwise go to DNS; this makes a TARGET
  like /ssh:example1.com: land where `ssh example1.com` does."
  [host]
  (let [res (process/shell {:out :string :err :string :continue true}
                           "ssh" "-G" host)]
    (when (zero? (:exit res))
      (into {} (keep #(let [[k v] (str/split % #" " 2)]
                        (when (#{"hostname" "user" "port"} k) [(keyword k) v])))
            (str/split-lines (:out res))))))

(defn target-session [{:keys [tag host user port hops]}]
  (when (> (count hops) 1)
    (throw (ex-info (str "@" tag ": multi-hop targets not supported in this example") {})))
  (let [live (get @sessions tag)]
    (if (and live (session/connected? live))
      live
      ;; Slots the TARGET spells out win; the rest comes from ssh config.
      ;; No credentials passed: the ssh-agent supplies keys, and Tailscale
      ;; SSH hosts authenticate by tailnet identity (none-auth) anyway.
      ;; :accept-host-key :new trusts unknown hosts but still rejects changed
      ;; keys -- the interactive "yes/no" prompt can't work in raw mode.
      (let [cfg  (ssh-config host)
            user (or user (:user cfg))
            port (or port (:port cfg))
            sess (ssh/ssh (or (:hostname cfg) host)
                          (cond-> {:accept-host-key :new
                                   :silence-messages true}
                            user (assoc :username user)
                            port (assoc :port (parse-long port))))]
        (swap! sessions assoc tag sess)
        sess))))

(defn run-remote
  "Run CMD on SPEC's host over the cached ssh session; block until done."
  [spec cmd]
  (let [sess (target-session spec)
        dir  (:dir spec)
        cmd  (if (str/blank? dir) cmd (str "cd " (pr-str dir) " && " cmd))
        {:keys [channel out err]} (ssh/exec sess cmd {:out :string :err :string})]
    {:exit (channel-exec/wait channel) :out @out :err @err}))

(defn run-local [dir cmd]
  (-> (process/shell {:dir dir :out :string :err :string :continue true}
                     "sh" "-c" cmd)
      (select-keys [:exit :out :err])))

(defn run-block
  "Run block B's :body on every target its heading resolves to; a block under
  no #+TARGET-tagged heading runs locally in the org file's directory.
  Returns [{:tag :result}] per target, or {:tag :error} when one fails."
  [targets-by-tag b]
  (let [body (:body b)
        run1 (fn [tag thunk]
               (try {:tag tag :result (thunk)}
                    (catch Exception e {:tag tag :error (ex-message e)})))]
    (if-let [ts (seq (:targets b))]
      (vec (for [{:keys [tag]} ts
                 :let [spec (get targets-by-tag tag)]]
             (run1 tag #(if (:remote? spec)
                          (run-remote spec body)
                          (run-local (:dir spec) body)))))
      [(run1 "local" #(run-local org-dir body))])))

(defn tangle-block
  "Tangle block B's heading through Emacs; remote :tangle paths go over TRAMP."
  [b]
  (if-let [h (:heading b)]
    (devops/tangle {:file org-file :heading h})
    (throw (ex-info "block sits before the first heading; nothing to tangle" {}))))

;;;; ---------------------------------------------------------- terminal drawing

(def ESC "")
(defn- clear       [] (str ESC "[2J" ESC "[H"))
(defn- hide-cursor [] (str ESC "[?25l"))
(defn- show-cursor [] (str ESC "[?25h"))

(defn crlf
  "Raw mode needs explicit CR: turn a command's \\n-separated output into \\r\\n."
  [s]
  (str/replace (or s "") "\n" "\r\n"))

(defn block-label [b]
  (or (:heading b) (:name b) (format "«block %d»" (:index b))))

(defn block-row [b]
  (str (format "%-28s %-5s %-12s"
               (block-label b)
               (:lang b)
               (if-let [ts (seq (:targets b))]
                 (str "@" (str/join ",@" (map :tag ts)))
                 "(local)"))
       (when-let [tg (:tangle b)]
         (when-not (#{"no" "yes"} tg) (str " ⇥ " tg)))))

(defn render [blocks idx]
  (str (clear) (hide-cursor)
       "Runbook: " org-file "\r\n"
       "↑/↓ or j/k move, Enter run on target, t tangle, q quit.\r\n\r\n"
       (str/join "\r\n"
                 (map-indexed
                  (fn [i b]
                    (if (= i idx)
                      (str ESC "[7m> " (block-row b) ESC "[0m")
                      (str "  " (block-row b))))
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

;;;; ------------------------------------------------------------------- actions

(def spinner ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn with-spinner
  "Run THUNK off the UI thread; animate a spinner until it finishes.
  Returns [:ok val] or [:err msg]."
  [terminal title thunk]
  (let [w      (.writer terminal)
        result (future (try [:ok (thunk)]
                            (catch Exception e [:err (ex-message e)])))]
    (loop [i 0]
      (if-not (realized? result)
        (do (.print w (str (clear) (hide-cursor)
                           title " " (nth spinner (mod i (count spinner))) "\r\n"))
            (.flush w)
            (Thread/sleep 120)
            (recur (inc i)))
        @result))))

(defn show!
  "Paint TEXT full-screen and wait for a key."
  [terminal text]
  (doto (.writer terminal)
    (.print (str (clear) (show-cursor) text
                 "\r\n\r\nPress any key to return …"))
    (.flush))
  (read-key (.reader terminal)))

(defn run-report [outcomes]
  (str/join "\r\n\r\n"
            (for [{:keys [tag result error]} outcomes]
              (if error
                (format "@%s ERROR: %s" tag error)
                (str (format "@%s exit %s" tag (:exit result))
                     "\r\n" (crlf (str/trim-newline (:out result)))
                     (when-not (str/blank? (:err result))
                       (str "\r\nstderr:\r\n"
                            (crlf (str/trim-newline (:err result))))))))))

(defn tangle-report [results]
  ;; each result is {:tag :target :files} where :files is a COUNT --
  ;; devops--tangle-spec-execute returns (TAG TARGET N), not filenames.
  (if (empty? results)
    "Nothing tangled (no :tangle blocks under this heading?)"
    (str/join "\r\n"
              (for [{:keys [tag target files]} results]
                (format "@%s %s file%s → %s"
                        tag files (if (= files 1) "" "s") target)))))

(defn run-selected! [terminal targets-by-tag b]
  (if-not (shell-langs (:lang b))
    (show! terminal (format "%s is a %s block; only %s run.  (t tangles it.)"
                            (block-label b) (:lang b) (str/join "/" (sort shell-langs))))
    ;; format inside the thunk: a surprise in the result shape becomes an
    ;; ERROR screen instead of an exception that tears down the whole TUI
    (let [[status val] (with-spinner terminal (str "Running " (block-label b))
                         #(run-report (run-block targets-by-tag b)))]
      (show! terminal (str "Ran " (block-label b) "\r\n\r\n"
                           (if (= status :ok) val (str "ERROR: " val)))))))

(defn tangle-selected! [terminal b]
  (let [[status val] (with-spinner terminal (str "Tangling " (block-label b))
                       #(tangle-report (tangle-block b)))]
    (show! terminal (str "Tangled " (block-label b) "\r\n\r\n"
                         (if (= status :ok) val (str "ERROR: " val))))))

(defn tui [terminal targets-by-tag blocks]
  (let [reader (.reader terminal)
        w      (.writer terminal)
        n      (count blocks)]
    (.enterRawMode terminal)
    (loop [idx 0]
      (.print w (render blocks idx)) (.flush w)
      (case (read-key reader)
        (:up   \k) (recur (mod (dec idx) n))
        (:down \j) (recur (mod (inc idx) n))
        :enter     (do (run-selected! terminal targets-by-tag (nth blocks idx)) (recur idx))
        \t         (do (tangle-selected! terminal (nth blocks idx)) (recur idx))
        (\q :quit) :done
        (recur idx)))))

;;;; --------------------------------------------------- dumb-terminal fallback

(defn fallback
  "No real TTY (piped/redirected): a plain numbered prompt instead of the TUI."
  [targets-by-tag blocks]
  (println "Runbook:" org-file)
  (doseq [[i b] (map-indexed vector blocks)]
    (println (format "  %d) %s" i (block-row b))))
  (loop []
    (print "N to run, t N to tangle, q to quit: ") (flush)
    (let [in    (str/trim (or (read-line) "q"))
          [c a] (str/split in #"\s+")]
      (cond
        (#{"q" ""} in) nil
        (= c "t") (do (if-let [b (some->> a parse-long (get blocks))]
                        (prn (tangle-block b))
                        (println "No such block:" a))
                      (recur))
        :else     (do (if-let [b (some->> c parse-long (get blocks))]
                        (doseq [{:keys [tag result error]} (run-block targets-by-tag b)]
                          (if error
                            (println (str "@" tag " ERROR: " error))
                            (do (println (str "@" tag " exit " (:exit result)))
                                (print (:out result))
                                (when-not (str/blank? (:err result))
                                  (binding [*out* *err*] (print (:err result)))))))
                        (println "No such block:" c))
                      (recur))))))

;;;; ---------------------------------------------------------------------- main

(let [targets-by-tag (into {} (map (juxt :tag identity)) (devops/targets {:file org-file}))
      blocks         (vec (devops/src-blocks {:file org-file}))
      terminal       (-> (TerminalBuilder/builder) (.system true) (.build))]
  (try
    (cond
      (empty? blocks)                (println "No src blocks in" org-file)
      (= "dumb" (.getType terminal)) (fallback targets-by-tag blocks)
      :else
      (try
        (tui terminal targets-by-tag blocks)
        (finally
          (doto (.writer terminal) (.print (str (show-cursor) "\r\n")) (.flush)))))
    (finally
      (doseq [[_ sess] @sessions]
        (try (session/disconnect sess) (catch Exception _)))
      (.close terminal))))   ; close restores the original terminal attributes

(comment
  (deps/add-deps '{:deps {djblue/portal {:mvn/version "0.65.0"}}})

  (require '[portal.api :as p])
  (def p (p/open))
  (add-tap #'p/submit)

  (tap> (devops/src-blocks {:file org-file}))
  (tap> (devops/targets {:file org-file}))

  ;; shell blocks only
  (tap> (->> (devops/src-blocks {:file org-file})
             (filter #(shell-langs (:lang %))))))
