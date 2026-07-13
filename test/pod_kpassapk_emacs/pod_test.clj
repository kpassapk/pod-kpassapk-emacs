(ns pod-kpassapk-emacs.pod-test
  "End-to-end tests for the pod-kpassapk-emacs pod.

  These load the actual pod executable once (which spawns an `emacs --batch'
  child), then exercise the EDN-returning API surface and assert on real
  values produced by a real Emacs/org-mode."
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

(def sample-org
  (.getPath (io/file repo-root "examples" "sample.org")))

;; Load the pod exactly once for the whole suite. load-pod is idempotent enough
;; for our needs, but we still only want to pay the ~2-4s emacs spawn once.
(defonce loaded
  (do
    (pods/load-pod [pod-path])
    (require '[pod.babashka.emacs :as emacs])
    (require '[pod.babashka.emacs.org :as org])
    true))

(use-fixtures :once (fn [t] (assert loaded) (t)))

;; Resolve the pod-provided fns at call time so the namespace compiles before
;; the pod is loaded.
(defn- ev [code]      ((resolve 'pod.babashka.emacs/eval) code))
(defn- funcall [& as] (apply (resolve 'pod.babashka.emacs/funcall) as))
(defn- version []     ((resolve 'pod.babashka.emacs/version)))
(defn- outline
  ([path]      ((resolve 'pod.babashka.emacs.org/outline) path))
  ([path opts] ((resolve 'pod.babashka.emacs.org/outline) path opts)))
(defn- headlines
  ([path opts] ((resolve 'pod.babashka.emacs.org/headlines) path opts)))
(defn- to-edn
  ([path]      ((resolve 'pod.babashka.emacs.org/to-edn) path)))
(defn- execute
  ([path]      ((resolve 'pod.babashka.emacs.org/execute) path))
  ([path opts] ((resolve 'pod.babashka.emacs.org/execute) path opts)))
(defn- src-blocks
  ([path]      ((resolve 'pod.babashka.emacs.org/src-blocks) path)))

;;;; ------------------------------------------------------------- helpers

(defn- find-node
  "Depth-first search of a node tree (root has :children) for the first node
  whose :title equals TITLE."
  [root title]
  (->> (tree-seq #(seq (:children %)) :children root)
       (filter #(= title (:title %)))
       first))

(defn- child-titles [node]
  (into [] (map :title) (:children node)))

;;;; ------------------------------------------------------------- describe / version

(deftest version-test
  (testing "version returns a map with an integer :major-version"
    (let [v (version)]
      (is (map? v))
      (is (integer? (:major-version v)))
      (is (pos? (:major-version v)))
      (is (string? (:emacs-version v))))))

;;;; ------------------------------------------------------------- deferred load

(deftest org-deferred-load-test
  (testing "org is a deferred namespace: it loads via load-ns on first require"
    ;; The `loaded' defonce already did (require 'pod.babashka.emacs.org). org is
    ;; advertised in describe as deferred (no vars), so that require triggered a
    ;; `load-ns' op which `require'd pod-emacs-org.el in the child and returned
    ;; its vars. If load-ns were broken the require — and the whole suite — would
    ;; have thrown. Here we just confirm the vars resolved and invoke cleanly.
    (is (some? (resolve 'pod.babashka.emacs.org/execute))
        "deferred org vars are present after require")
    (is (= "hello" (execute sample-org {:index 0}))
        "an org var invokes correctly through the deferred-loaded namespace"))

  (testing "a second require of the deferred namespace is idempotent"
    (require '[pod.babashka.emacs.org :as org] :reload)
    (is (= 2 (count (vec (src-blocks sample-org)))))))

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

(deftest eval-unicode-test
  (testing "unicode round-trips exactly"
    (is (= "héllo ✓ 日本" (ev "\"héllo ✓ 日本\"")))))

(deftest eval-large-payload-test
  (testing "a 50000-char string round-trips intact"
    (let [s (ev "(make-string 50000 ?x)")]
      (is (string? s))
      (is (= 50000 (count s)))
      (is (= (apply str (repeat 50000 \x)) s)))))

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

;;;; ------------------------------------------------------------- org

(deftest org-outline-test
  (let [{:keys [title children] :as root} (outline sample-org)]
    (testing "file title"
      (is (= "Project Roadmap" title)))

    (testing "top-level children titles, in document order"
      (is (= ["Planning" "Implementation" "Done"] (into [] (map :title) children))))

    (testing "Define scope node fields"
      (let [scope (find-node root "Define scope")]
        (is (some? scope) "Define scope node should exist")
        (is (= "TODO" (:todo scope)))
        (is (= "A" (:priority scope)))
        (is (contains? (set (:tags scope)) "urgent"))
        (is (= "scope" (get-in scope [:properties :CUSTOM_ID])))
        (is (some? (:scheduled scope)) "scheduled should be present")))

    (testing "nesting: Planning -> Define scope -> {Gather requirements, Write one-pager}"
      (let [planning (first (filter #(= "Planning" (:title %)) children))]
        (is (some? planning))
        (is (contains? (set (child-titles planning)) "Define scope"))
        (let [scope (first (filter #(= "Define scope" (:title %))
                                   (:children planning)))]
          (is (some? scope))
          (is (= #{"Gather requirements" "Write one-pager"}
                 (set (child-titles scope)))))))))

(deftest org-headlines-max-level-test
  (testing "headlines with {:max-level 1} returns only level-1 nodes"
    (let [hs (headlines sample-org {:max-level 1})]
      (is (sequential? hs))
      (is (every? #(= 1 (:level %)) hs))
      (is (= ["Planning" "Implementation" "Done"] (into [] (map :title) hs))))))

(deftest org-to-edn-body-test
  (testing "to-edn includes a :body somewhere in the tree"
    (let [{:keys [children]} (to-edn sample-org)
          all (tree-seq #(seq (:children %)) :children {:children children})]
      (is (some :body all) "at least one node should carry :body text"))))

;;;; ------------------------------------------------------------- org/src-blocks

(deftest org-src-blocks-test
  (testing "src-blocks lists every block in document order with rich fields"
    (let [bs (vec (src-blocks sample-org))]
      (is (= 2 (count bs)) "sample.org has a sh block and a yaml block")
      (is (= [0 1] (mapv :index bs)) "indices are 0-based, in document order")
      (is (= ["sh" "yaml"] (mapv :lang bs)))
      (is (every? :begin bs) "each block carries a buffer position")
      (is (= "echo hello" (:body (first bs))))
      (is (= "sample.yaml" (get-in bs [1 :header-args :tangle]))
          "header-args surfaces babel params like :tangle")))

  (testing ":index from src-blocks round-trips into execute"
    (let [bs (vec (src-blocks sample-org))]
      (is (= "hello" (execute sample-org {:index (:index (first bs))}))))))

;;;; ------------------------------------------------------------- org/execute

(deftest org-execute-test
  (testing ":index 0 runs the first src block (sh echo) and returns its output"
    (is (= "hello" (execute sample-org {:index 0}))))

  (testing ":index out of range throws with a range message"
    (let [e (try (execute sample-org {:index 99}) (catch Exception e e))]
      (is (some? e))
      (is (re-find #"(?i)out of range" (ex-message e)))))

  (testing "no selector on a multi-block file throws and asks to disambiguate"
    (let [e (try (execute sample-org) (catch Exception e e))]
      (is (some? e))
      (is (re-find #":name or :index" (ex-message e)))))

  (testing ":name selects by #+name: and the lang backend autoloads"
    (let [tmp (java.io.File/createTempFile "pod-exec" ".org")]
      (try
        (spit tmp (str "#+name: greet\n"
                       "#+begin_src sh\necho hi-from-name\n#+end_src\n\n"
                       "#+name: addup\n"
                       "#+begin_src emacs-lisp\n(+ 40 2)\n#+end_src\n"))
        (is (= "hi-from-name" (execute (.getPath tmp) {:name "greet"})))
        (is (= 42 (execute (.getPath tmp) {:name "addup"})))
        (finally (.delete tmp))))))
