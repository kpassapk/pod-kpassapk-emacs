#!/usr/bin/env bb
;; eve — a tiny TUI text editor where Emacs does the editing.
;;
;; Stress test for pod-kpassapk-emacs: every keystroke is an elisp
;; round-trip. The buffer, point, kill-ring and undo history all live in
;; a real Emacs process; babashka only reads keys and paints the screen.
;; The status bar shows the pod round-trip latency of the last render.
;;
;; Usage: bb editor.bb [file]
;;
;; Keys:  C-f/C-b/C-n/C-p or arrows   move
;;        C-a/C-e   line start/end    M-f/M-b   word motion
;;        M-< / M->  buffer start/end
;;        C-d delete   C-k kill line   C-y yank   C-/ undo
;;        M-u/M-l/M-c  upcase/downcase/capitalize word
;;        C-x C-s save    C-x C-c quit

(require '[babashka.pods :as pods]
         '[babashka.process :refer [shell]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(pods/load-pod [(.getPath (io/file here ".." "target" "release" "pod-kpassapk-emacs"))])
(require '[pod.kpassapk.emacs :as emacs])

;; --- elisp plumbing --------------------------------------------------------

(defn el-str [s]
  (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn in-buffer [& forms]
  (str "(with-current-buffer (get-buffer-create \"*eve*\") "
       (str/join " " (remove nil? forms)) ")"))

(def last-ms (atom 0.0))
(def message* (atom "C-x C-s save · C-x C-c quit"))

(defn eval! [form]
  (let [t0 (System/nanoTime)]
    (try
      (let [r (emacs/eval form)]
        (reset! last-ms (/ (- (System/nanoTime) t0) 1e6))
        r)
      (catch clojure.lang.ExceptionInfo e
        (reset! last-ms (/ (- (System/nanoTime) t0) 1e6))
        (reset! message* (str "emacs: " (ex-message e)))
        nil))))

(defn edit!
  "Runs edit forms in the *eve* buffer, closing an undo group per keystroke
  (the pod has no command loop, so nobody pushes undo boundaries for us)."
  [& forms]
  (eval! (apply in-buffer (concat forms ["(undo-boundary)"]))))

(def undoing? (atom false))

(defn undo!
  "Consecutive undos must look like one continued undo to Emacs, which
  normally infers that from last-command — emulate it from the bb side."
  []
  (edit! (if @undoing? "(setq last-command 'undo)" "(setq last-command nil)")
         "(undo)")
  (reset! undoing? true))

;; One round-trip returns everything the renderer needs.
(defn snapshot []
  (eval! (in-buffer "(list (buffer-substring-no-properties (point-min) (point-max))"
                    "(line-number-at-pos)"
                    "(current-column)"
                    "(if (buffer-modified-p) 1 0))")))

;; --- terminal --------------------------------------------------------------

(def stdin System/in)

(defn read-key
  "Returns an int char code, :up/:down/:left/:right/:delete, [:meta ch], or :esc."
  []
  (let [c (.read stdin)]
    (if (not= c 27)
      c
      (do (Thread/sleep 10)
          (if (zero? (.available stdin))
            :esc
            (let [c2 (.read stdin)]
              (if (= c2 91) ; ESC [
                (case (.read stdin)
                  65 :up 66 :down 67 :right 68 :left
                  51 (do (.read stdin) :delete) ; ESC [ 3 ~
                  :esc)
                [:meta (char c2)])))))))

(defn render
  "Paints the buffer snapshot; returns the new scroll offset."
  [rows cols top file]
  (let [[text line col modified] (snapshot)
        lines (vec (str/split (or text "") #"\n" -1))
        h (dec rows)
        top (-> (cond
                  (< (dec line) top)      (dec line)
                  (>= (dec line) (+ top h)) (- line h)
                  :else top)
                (max 0))
        status (str " *eve* " (if file (.getName (io/file file)) "(no file)")
                    "  L" line ":" col
                    (if (= 1 modified) "  **" "  --")
                    (format "  pod %.1fms  " @last-ms)
                    @message*)
        status (subs (str status (apply str (repeat cols " "))) 0 cols)
        sb (StringBuilder. "[?25l[H")]
    (doseq [i (range h)]
      (let [l (get lines (+ top i))]
        (.append sb "[2K")
        (.append sb (cond
                      (nil? l) "~"
                      (> (count l) cols) (subs l 0 cols)
                      :else l))
        (.append sb "\r\n")))
    (.append sb (str "[7m" status "[0m"))
    (.append sb (str "[" (- line top) ";" (inc col) "H[?25h"))
    (print (str sb))
    (flush)
    top))

;; --- key dispatch ----------------------------------------------------------

(def ctrl-keys
  {1  "(move-beginning-of-line 1)"                                          ; C-a
   2  "(backward-char)"                                                     ; C-b
   4  "(delete-char 1)"                                                     ; C-d
   5  "(move-end-of-line 1)"                                                ; C-e
   6  "(forward-char)"                                                      ; C-f
   11 "(kill-line)"                                                         ; C-k
   13 "(newline)"                                                           ; RET
   14 "(let ((c (current-column))) (forward-line 1) (move-to-column c))"    ; C-n
   16 "(let ((c (current-column))) (forward-line -1) (move-to-column c))"   ; C-p
   25 "(yank)"})                                                            ; C-y

(def meta-keys
  {\f "(forward-word)"  \b "(backward-word)"  \d "(kill-word 1)"
   \< "(goto-char (point-min))" \> "(goto-char (point-max))"
   \u "(upcase-word 1)" \l "(downcase-word 1)" \c "(capitalize-word 1)"})

(def arrow-keys
  {:up    (ctrl-keys 16) :down (ctrl-keys 14)
   :left  (ctrl-keys 2)  :right (ctrl-keys 6)
   :delete (ctrl-keys 4)})

(defn save! [file]
  (if file
    (do (edit! (str "(write-region (point-min) (point-max) " (el-str file) ")")
               "(set-buffer-modified-p nil)")
        (reset! message* (str "Wrote " file)))
    (reset! message* "No file to save (start with: bb editor.bb FILE)")))

(defn handle
  "Applies one key; returns :quit to exit."
  [k file]
  (when-not (= k 31) (reset! undoing? false))
  (cond
    (= k 31) (undo!)                                         ; C-/
    (arrow-keys k) (edit! (arrow-keys k))

    (vector? k)
    (if-let [form (meta-keys (second k))]
      (edit! form)
      (reset! message* (str "M-" (second k) " undefined")))

    (= k :esc) nil
    (= k 24) ; C-x prefix
    (case (int (read-key))
      19 (save! file)   ; C-x C-s
      3  :quit          ; C-x C-c
      (reset! message* "C-x ?"))

    (= k 7)  (reset! message* "")                            ; C-g
    (= k 12) nil                                             ; C-l repaint
    (= k 9)  (edit! "(insert \"    \")")                     ; TAB as spaces
    (= k 127) (edit! "(delete-char -1)")                     ; backspace
    (ctrl-keys k) (edit! (ctrl-keys k))
    (<= 32 k 126) (edit! (str "(insert " (el-str (str (char k))) ")"))
    :else (reset! message* (str "key " k " undefined"))))

;; --- main ------------------------------------------------------------------

(defn -main [& args]
  (let [file (first args)
        [rows cols] (map parse-long
                         (str/split (str/trim (:out (shell {:out :string} "stty" "size"))) #" "))]
    (edit! "(buffer-disable-undo)"
           (when (and file (.exists (io/file file)))
             (str "(insert-file-contents " (el-str file) ")"))
           "(set-buffer-modified-p nil)"
           "(goto-char (point-min))"
           "(buffer-enable-undo)")
    (shell "stty" "raw" "-echo")
    (print "[?1049h")
    (try
      (loop [top 0]
        (let [top (render rows cols top file)]
          (when-not (= :quit (handle (read-key) file))
            (recur top))))
      (finally
        (print "[?1049l[?25h")
        (flush)
        (shell "stty" "sane")))))

(apply -main *command-line-args*)
