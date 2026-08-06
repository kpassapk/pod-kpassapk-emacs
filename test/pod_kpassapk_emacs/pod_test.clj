(ns pod-kpassapk-emacs.pod-test
  "End-to-end tests for the pod-kpassapk-emacs pod.

  These load the actual pod executable once (which spawns an `emacs --batch'
  child), then exercise the EDN-returning API surface and assert on real
  values produced by a real Emacs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [babashka.pods :as pods]))

;;;; ------------------------------------------------------------- setup

(def repo-root
  "Absolute path to the repo root (this file lives at test/pod_babashka_emacs/)."
  (-> (io/file *file*)
      .getCanonicalFile
      .getParentFile            ; pod_babashka_emacs
      .getParentFile            ; test
      .getParentFile))          ; repo root

(def pod-path
  "The pod binary under test: $POD_KPASSAPK_EMACS_POD if set, else the release
  build (bb test builds it first)."
  (or (System/getenv "POD_KPASSAPK_EMACS_POD")
      (.getPath (io/file repo-root "target" "release" "pod-kpassapk-emacs"))))

;; Load the pod exactly once for the whole suite. load-pod is idempotent enough
;; for our needs, but we still only want to pay the ~2-4s emacs spawn once.
(defonce loaded
  (do
    (pods/load-pod [pod-path])
    (require '[pod.kpassapk.emacs :as emacs])
    true))

(use-fixtures :once (fn [t] (assert loaded) (t)))

;; Resolve the pod-provided fns at call time so the namespace compiles before
;; the pod is loaded.
(defn- ev [code]      ((resolve 'pod.kpassapk.emacs/eval) code))
(defn- funcall [& as] (apply (resolve 'pod.kpassapk.emacs/funcall) as))
(defn- version []     ((resolve 'pod.kpassapk.emacs/version)))
(defn- install! [d]   ((resolve 'pod.kpassapk.emacs/install!) d))

;;;; ------------------------------------------------------------- describe / version

(deftest version-test
  (testing "version returns a map with an integer :major-version"
    (let [v (version)]
      (is (map? v))
      (is (integer? (:major-version v)))
      (is (pos? (:major-version v)))
      (is (string? (:emacs-version v))))))

;;;; ------------------------------------------------------------- install!

;; These use packages that ship with Emacs, so the suite stays offline: no
;; archive is contacted for a declaration that needs neither `:ensure' nor
;; `:vc'. What is under test is the use-package plumbing and the
;; did-it-actually-install post-condition, not the download.

(deftest install-test
  (testing "a bare symbol loads the package and returns its name"
    (is (= "subr-x" (install! 'subr-x)))
    (is (= true (ev "(featurep 'subr-x)"))))

  (testing "a declaration with keywords is evaluated as use-package"
    (is (= "calc" (install! '(calc :config (setq calc-test-marker 1)))))
    (is (= 1 (ev "calc-test-marker")) ":config ran"))

  (testing "install! is idempotent"
    (is (= "subr-x" (install! 'subr-x))))

  (testing "a package that does not install throws, naming the package"
    (let [e (try (install! 'no-such-package-xyz) (catch Exception e e))]
      (is (some? e))
      (is (re-find #"no-such-package-xyz" (ex-message e)))))

  (testing "a missing declaration throws"
    (let [e (try (install! nil) (catch Exception e e))]
      (is (some? e))
      (is (re-find #"missing package declaration" (ex-message e))))))

;;;; ------------------------------------------------------------- eval

(deftest eval-basic-test
  (testing "integer arithmetic"
    (is (= 3 (ev "(+ 1 2)"))))

  (testing "number-sequence returns the expected sequence"
    (is (= [1 2 3 4 5] (into [] (ev "(number-sequence 1 5)")))))

  (testing "string upcase"
    (is (= "HELLO" (ev "(upcase \"hello\")"))))

  (testing "multiple top-level forms; last value is returned"
    (is (= 3 (ev "(setq x 1)(setq y 2)(+ x y)")))))

(deftest eval-hash-table-test
  (testing "elisp hash-table with keyword keys -> Clojure map"
    (let [m (ev (str "(let ((h (make-hash-table :test 'equal)))"
                     "  (puthash :a 1 h)"
                     "  (puthash :b 2 h)"
                     "  h)"))]
      (is (map? m))
      (is (= 1 (:a m)))
      (is (= 2 (:b m))))))

(deftest eval-nested-test
  (testing "nested list/vector/list structure round-trips structurally"
    ;; (list 1 (vector 2 3) (list :k \"v\")) -> [1 [2 3] {:k \"v\"}]-ish.
    ;; elisp list -> Clojure list/seq, vector -> vector. Assert structurally so
    ;; we don't depend on seq vs vector at the top level.
    (let [r (ev "(list 1 (vector 2 3) (list :k \"v\"))")
          v (vec r)]
      (is (= 3 (count v)))
      (is (= 1 (nth v 0)))
      (is (vector? (nth v 1)))
      (is (= [2 3] (nth v 1)))
      ;; The keyword-led elisp list (:k "v") decodes to a Clojure map {:k "v"}.
      (is (= {:k "v"} (nth v 2)))
      ;; And the whole thing matches the expected shape.
      (is (= [1 [2 3] {:k "v"}] v)))))

(deftest edn-value-mapping-test
  (testing "elisp writes a map three ways and all three cross as maps"
    (is (= {:a 1 :b 2} (ev "(list :a 1 :b 2)")) "plist")
    (is (= {"a" 1 "b" 2} (ev "(list (cons \"a\" 1) (cons \"b\" 2))")) "alist")
    (is (= {:a 1} (ev "(let ((h (make-hash-table))) (puthash :a 1 h) h)"))
        "hash-table"))
  (testing "a list that is neither shape stays a list"
    ;; A plist needs a keyword in every key slot and an even length; an alist
    ;; needs a cons with an atom car for every element.
    (is (= '("a" 1) (ev "(list \"a\" 1)")))
    (is (= '(:a 1 :b) (ev "(list :a 1 :b)")))
    (is (= '(:a 1 "b" 2) (ev "(list :a 1 \"b\" 2)"))))
  (testing "strings survive the characters EDN has to escape"
    (is (= "a\"b\\c\nd\te\rf" (ev "\"a\\\"b\\\\c\nd\te\rf\""))))
  (testing "a value EDN cannot carry is stringified where it stands"
    (let [m (ev "(list :ok 1 :buf (current-buffer) :pair (cons 1 2))")]
      (is (= 1 (:ok m)) "the reply around it is still data")
      (is (string? (:buf m)))
      (is (= "(1 . 2)" (:pair m)) "a dotted pair has no EDN form"))))

(deftest eval-unicode-test
  (testing "unicode round-trips exactly"
    (is (= "héllo ✓ 日本" (ev "\"héllo ✓ 日本\"")))))

(deftest eval-large-payload-test
  (testing "a 50000-char string round-trips intact"
    (let [s (ev "(make-string 50000 ?x)")]
      (is (string? s))
      (is (= 50000 (count s)))
      (is (= (apply str (repeat 50000 \x)) s)))))

(deftest eval-stdout-guard-test
  (testing "user elisp writing to stdout does not corrupt the protocol"
    ;; stdout is the protocol channel; `standard-output' is rebound to stderr
    ;; in pod-emacs-main so princ/print/pp can't break the base64 framing.
    (is (= 42 (ev "(princ \"BOOM\") 42")))
    (is (= 7 (ev "(print 99) 7"))))

  (testing "the session survives calls that wrote to standard-output"
    (is (= 4 (ev "(+ 2 2)")))))

(deftest eval-buffer-switch-test
  (testing "a call that leaves another buffer current does not corrupt the
            protocol (drain/insert must re-anchor to the pod input buffer)"
    (let [tmp (java.io.File/createTempFile "pod-visit" ".txt")]
      (try
        (spit tmp "hello from the file\n")
        (is (= (.getName tmp) (ev (format "(find-file %s)(buffer-name)"
                                          (pr-str (.getPath tmp))))))
        ;; the next call used to die decoding the visited file's text
        (is (= 3 (ev "(+ 1 2)")))
        (finally (.delete tmp))))))

(deftest eval-error-test
  (testing "arithmetic error throws with an Arith* message"
    (let [e (try (ev "(/ 1 0)") (catch Exception e e))]
      (is (some? e))
      (is (instance? Exception e))
      (is (re-find #"(?i)Arith" (ex-message e)))))

  (testing "calling an undefined function throws with :type void-function"
    (let [e (try (ev "(no-such-fn-xyz)") (catch Exception e e))]
      (is (some? e))
      (is (= "void-function" (:type (ex-data e)))))))

;;;; ------------------------------------------------------------- clj!

;; clj! is a client-side macro shipped in the describe reply's `code' field:
;; its body is Clojure, compiled by cljbang inside the emacs child. The pod is
;; already loaded (the `loaded' defonce above runs before these forms are
;; read), so the macro can be referenced directly.

(deftest clj-basic-test
  (testing "arithmetic"
    (is (= 3 (pod.kpassapk.emacs/clj! (+ 1 2)))))

  (testing "threading, higher-order fns, vectors"
    (is (= [2 4] (pod.kpassapk.emacs/clj!
                  (->> [1 2 3 4] (filter odd?) (mapv inc))))))

  (testing "#() literals compile (rewritten fn* -> fn by the macro)"
    (is (= [2 4 6] (pod.kpassapk.emacs/clj! (mapv #(* 2 %) [1 2 3])))))

  (testing "multiple forms; last value returned"
    (is (= 3 (pod.kpassapk.emacs/clj! (def clj-test-x 1) (+ clj-test-x 2))))))

(deftest clj-interpolation-test
  (testing "~ interpolates a babashka value"
    (let [n 40]
      (is (= 42 (pod.kpassapk.emacs/clj! (+ ~n 2))))))

  (testing "~ interpolates strings and collections"
    (let [s "hi" v [1 2 3]]
      (is (= "HI" (pod.kpassapk.emacs/clj! (el/upcase ~s))))
      (is (= 3 (pod.kpassapk.emacs/clj! (count ~v))))))

  (testing "~@ splices a collection into a call"
    (let [xs [1 2 3]]
      (is (= 6 (pod.kpassapk.emacs/clj! (+ ~@xs))))))

  (testing "~ inside nested vectors and maps"
    (let [v 9]
      (is (= [1 9] (pod.kpassapk.emacs/clj! [1 ~v])))
      (is (= 9 (pod.kpassapk.emacs/clj! (get {:a ~v} :a)))))))

(deftest clj-session-test
  (testing "defn persists across clj! calls for the pod session"
    (pod.kpassapk.emacs/clj! (defn clj-test-double [x] (* 2 x)))
    (is (= 42 (pod.kpassapk.emacs/clj! (clj-test-double 21))))))

(deftest clj-interop-test
  (testing "el/ calls Emacs Lisp directly"
    (is (= "HELLO" (pod.kpassapk.emacs/clj! (el/upcase "hello"))))
    (is (string? (pod.kpassapk.emacs/clj! (el/emacs-version)))))

  (testing "maps round-trip: cljbang hash-table out, EDN map back"
    (is (= {:a 1 :b [1 2]} (pod.kpassapk.emacs/clj! {:a 1 :b [1 2]})))))

(deftest clj-set-encoding-test
  (testing "a cljbang set comes back as a set, nested or not"
    (is (= #{"a" "b"} (pod.kpassapk.emacs/clj! #{"a" "b"})))
    (is (= {:tags #{"dev"}} (pod.kpassapk.emacs/clj! {:tags #{"dev"}}))))

  (testing "a value the EDN printer refuses is stringified where it stands,
            not by turning the whole reply into one string"
    (let [m (pod.kpassapk.emacs/clj! {:ok 1 :buf (el/current-buffer)})]
      (is (map? m))
      (is (= 1 (:ok m)))
      (is (string? (:buf m))))))

(deftest clj-error-test
  (testing "an undefined elisp function throws through the pod"
    (let [e (try (pod.kpassapk.emacs/clj! (el/no-such-fn-xyz))
                 (catch Exception e e))]
      (is (some? e)))))

(deftest eval-clj-string-test
  (testing "eval-clj also accepts a raw Clojure source string"
    (is (= 6 ((resolve 'pod.kpassapk.emacs/eval-clj)
              "(reduce + 0 [1 2 3])")))))

;;;; ------------------------------------------------------------- funcall

(deftest funcall-test
  (testing "calls a named elisp function with EDN-marshalled data args"
    (is (= "HI" (funcall "upcase" "hi")))
    (is (= 6   (funcall "+" 1 2 3)))
    (is (= "x-7" (funcall "format" "%s-%d" "x" 7))))

  (testing "keyword and vector args round-trip into elisp"
    (is (= ":foo" (funcall "symbol-name" :foo)))
    (is (= 3 (funcall "length" [10 20 30]))))

  (testing "an unknown function throws with :type void-function"
    (let [e (try (funcall "no-such-fn-xyz" 1) (catch Exception e e))]
      (is (= "void-function" (:type (ex-data e)))))))
