#!/usr/bin/env bb
;; A unit converter TUI that hands every calculation to Emacs' built-in Calc.
;;
;;   bb examples/calc-units.bb                 ; interactive TUI
;;   bb examples/calc-units.bb 100 mi km       ; one-shot, prints "160.9344 km"
;;
;; The pod exposes Calc's units engine as `calc/convert' (and `calc/eval' for
;; the affine temperature scales Calc treats as non-multiplicative).  babashka
;; owns the UI; Emacs owns the arithmetic.  Conversions are synchronous and
;; instant, so we just recompute the result on every keystroke.

(require '[babashka.pods :as pods]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.utils NonBlockingReader])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def pod  (.getPath (io/file here ".." "pod-babashka-emacs")))

(pods/load-pod [pod])
(require '[pod.babashka.emacs.calc :as calc])

;;;; ------------------------------------------------------------------ unit data

(def categories
  [{:name "Length"      :units ["mm" "cm" "m" "km" "in" "ft" "yd" "mi" "nmi"]}
   {:name "Mass"        :units ["mg" "g" "kg" "t" "oz" "lb" "ton"]}
   {:name "Speed"       :units ["m/s" "kph" "mph" "knot"]}
   {:name "Volume"      :units ["mL" "L" "tsp" "tbsp" "cup" "floz" "pt" "qt" "gal"]}
   {:name "Time"        :units ["s" "min" "hr" "day" "wk" "yr"]}
   {:name "Area"        :units ["cm^2" "m^2" "km^2" "in^2" "ft^2" "acre" "ha"]}
   {:name "Energy"      :units ["J" "kJ" "cal" "Cal" "kWh" "eV" "Btu"]}
   {:name "Pressure"    :units ["Pa" "kPa" "bar" "atm" "psi" "mmHg"]}
   {:name "Temperature" :units ["degC" "degF" "K"]}])

;;;; ----------------------------------------------------------------- conversion

(def temp-units #{"degC" "degF" "K"})

;; Calc's `convert' multiplies units, so it gets °F→°C wrong (it scales the
;; offset away).  The temperature scales are affine, so we normalise to Celsius
;; and back out with explicit formulas — still evaluated by Calc, via `eval'.
(def ->celsius {"degC" "(%s)" "degF" "((%s)-32)*5/9" "K" "(%s)-273.15"})
(def celsius-> {"degC" "(%s)" "degF" "(%s)*9/5+32"   "K" "(%s)+273.15"})

(defn- blankish? [s] (contains? #{"" "-" "." "-." "+"} (str/trim s)))

(defn convert
  "Convert AMOUNT FROM-unit into TO-unit via Calc. Returns [:ok str] or [:err msg]."
  [amount from to]
  (let [amount (if (blankish? amount) "0" amount)]
    (try
      (cond
        ;; both temperatures: affine path through calc/eval
        (and (temp-units from) (temp-units to))
        [:ok (str (calc/eval (format (celsius-> to)
                                     (format (->celsius from) amount)))
                  " " to)]
        ;; mixing a temperature with a non-temperature is meaningless
        (or (temp-units from) (temp-units to))
        [:err (str "can't convert between " from " and " to)]
        :else
        [:ok (calc/convert (str amount " " from) to)])
      (catch Exception e [:err (ex-message e)]))))

;;;; ---------------------------------------------------------- terminal drawing

(def ESC "")
(defn- clear       [] (str ESC "[2J" ESC "[H"))
(defn- hide-cursor [] (str ESC "[?25l"))
(defn- show-cursor [] (str ESC "[?25h"))
(defn- rev    [s] (str ESC "[7m" s ESC "[0m"))
(defn- bold   [s] (str ESC "[1m" s ESC "[0m"))
(defn- dim    [s] (str ESC "[2m" s ESC "[0m"))

(def rule (apply str (repeat 52 "─")))

(defn- unit-cell
  "One unit row, 16 cols wide. Highlighted when selected; reverse-video when its
  column also has focus."
  [u selected? focused?]
  (let [txt (format "%s%-14s" (if selected? "▸ " "  ") u)]
    (cond
      (and selected? focused?) (rev txt)
      selected?                (bold txt)
      :else                    txt)))

(defn render
  [{:keys [cat-idx from-idx to-idx amount focus]}]
  (let [{:keys [name units]} (nth categories cat-idx)
        [status result]      (convert amount (nth units from-idx) (nth units to-idx))
        col-hdr (fn [label focused?] (format "  %s" (if focused? (bold (format "%-14s" label))
                                                        (dim (format "%-14s" label)))))]
    (str
     (clear) (hide-cursor)
     (bold "Calc Unit Converter") "\r\n"
     rule "\r\n"
     "Category   " (rev (format " %s " name))
     "   " (dim (format "(%d/%d, [ ] to change)" (inc cat-idx) (count categories))) "\r\n\r\n"
     "Amount     " (bold (str amount "▏")) "\r\n\r\n"
     (col-hdr "From" (= focus :from)) (col-hdr "To" (= focus :to)) "\r\n"
     (str/join "\r\n"
               (map-indexed
                (fn [i u]
                  (str (unit-cell u (= i from-idx) (= focus :from))
                       (unit-cell u (= i to-idx)   (= focus :to))))
                units))
     "\r\n\r\n" rule "\r\n"
     (if (= status :ok)
       (bold (format "%s %s  =  %s" amount (nth units from-idx) result))
       (str ESC "[31m" result ESC "[0m"))
     "\r\n\r\n"
     (dim "↑/↓ select   Tab switch column   [ ] category   0-9 . - type   ⌫ delete   q quit")
     "\r\n")))

(defn read-key
  "Block for one keystroke; decode arrow escapes and control keys to keywords."
  [^NonBlockingReader r]
  (let [c (.read r)]
    (cond
      (= c 27)  (let [c2 (.read r 50)]                ; ESC: maybe an arrow sequence
                  (if (= c2 (int \[))
                    (case (char (.read r 50))
                      \A :up \B :down \C :right \D :left :other)
                    :esc))
      (= c 9)                :tab
      (or (= c 13) (= c 10)) :enter
      (or (= c 127) (= c 8)) :backspace
      (or (= c 3) (= c 4))   :quit                    ; Ctrl-C / Ctrl-D
      (neg? c)               :quit                    ; EOF
      :else (char c))))

;;;; ------------------------------------------------------------------ edit ops

(defn- edit-amount [amount c]
  (cond
    (Character/isDigit ^char c)              (str amount c)
    (and (= c \.) (not (str/includes? amount "."))) (str amount c)
    (and (= c \-) (str/blank? amount))       "-"
    :else amount))

;;;; ---------------------------------------------------------------------- tui

(defn tui [terminal]
  (let [reader (.reader terminal)
        w      (.writer terminal)
        ncats  (count categories)
        nunits #(count (:units (nth categories %)))]
    (.enterRawMode terminal)
    (loop [{:keys [cat-idx from-idx to-idx focus] :as st}
           {:cat-idx 0 :from-idx 0 :to-idx 1 :amount "1" :focus :from}]
      (.print w (render st)) (.flush w)
      (let [n   (nunits cat-idx)
            sel (if (= focus :from) :from-idx :to-idx)
            cur (if (= focus :from) from-idx to-idx)
            k   (read-key reader)]
        (cond
          (#{:up \k} k)                 (recur (assoc st sel (mod (dec cur) n)))
          (#{:down \j} k)               (recur (assoc st sel (mod (inc cur) n)))
          (#{:tab \h \l :left :right} k) (recur (assoc st :focus (if (= focus :from) :to :from)))
          (= \[ k) (let [c (mod (dec cat-idx) ncats)]
                     (recur (assoc st :cat-idx c :from-idx 0 :to-idx (min 1 (dec (nunits c))))))
          (= \] k) (let [c (mod (inc cat-idx) ncats)]
                     (recur (assoc st :cat-idx c :from-idx 0 :to-idx (min 1 (dec (nunits c))))))
          (= :backspace k) (recur (update st :amount #(subs % 0 (max 0 (dec (count %))))))
          (#{\q :quit} k)  :done
          (char? k)        (recur (update st :amount edit-amount k))
          :else            (recur st))))))

;;;; -------------------------------------------------- non-interactive fallbacks

(defn one-shot
  "bb examples/calc-units.bb 100 mi km -> print the result and exit."
  [amount from to]
  (let [[status result] (convert amount from to)]
    (if (= status :ok)
      (println result)
      (binding [*out* *err*] (println "error:" result) (System/exit 1)))))

;;;; ---------------------------------------------------------------------- main

(let [args *command-line-args*]
  (if (= 3 (count args))
    (apply one-shot args)
    (let [terminal (-> (TerminalBuilder/builder) (.system true) (.build))]
      (if (= "dumb" (.getType terminal))
        (do (println "calc-units needs a real terminal for the TUI.")
            (println "Try a one-shot conversion instead, e.g.:")
            (println "  bb examples/calc-units.bb 100 mi km")
            (.close terminal))
        (try
          (tui terminal)
          (finally
            (doto (.writer terminal) (.print (str (show-cursor) "\r\n")) (.flush))
            (.close terminal)))))))   ; close restores the original terminal attributes
