#!/usr/bin/env nbb
;; The portable suite on nbb — no build step, no JVM.
;;
;; Every source and every test namespace here is `.cljc`, and until this
;; file existed `clojure -M:test` was the only runtime that ever ran them,
;; so a defect in the ClojureScript half of a `.cljc` was invisible
;; (ADR-2608190100).
;;
;;   nbb --classpath "src:test:$(clojure -Spath -M:test)" run-tests.cljs
;;
;; The classpath needs the git dep (langchain); nbb does not read deps.edn.
;; On the fleet that is what `:ship-git-deps true` supplies.
;;
;; ONE namespace is missing from this list and it is not an oversight:
;;
;;   langgraph.kg-checkpoint-test
;;
;; `langgraph/kg_checkpoint.cljc:65` contains the keyword `:kg/claim/thread`.
;; Two slashes: the Clojure reader accepts it, the ClojureScript reader does
;; not ("Invalid keyword: :kg/claim/thread."), so the namespace cannot even
;; be read here. That is a real portability defect in the attribute name
;; rather than in this runner, and renaming an attribute is a schema change
;; this file is not the place to make. Until it is made, this runner is 38
;; of the JVM's 45 tests, and the seven are that one namespace.
;;
;; Every deftest-bearing portable namespace is named BOTH in the require and
;; in the `run-tests` call: requiring registers the vars, only `run-tests`
;; runs them, and a runner naming a subset prints the same `Ran N tests`
;; shape as one naming all of them.
(ns run-tests
  (:require [cljs.test :as t]
            [langgraph.agent-loop-test]
            [langgraph.agent-test]
            [langgraph.graph-test]
            [langgraph.kotoba-checkpoint-test]
            [langgraph.operator-quickstart-test]
            [langgraph.superstep-test]
            [langgraph.thread-custody-test]
            [langgraph.viz-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\nnbb: " (:test m) " tests, " (:pass m) " passed, "
                (:fail m) " failed, " (:error m) " errors"))
  (when (pos? (+ (or (:fail m) 0) (or (:error m) 0)))
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'langgraph.agent-loop-test
             'langgraph.agent-test
             'langgraph.graph-test
             'langgraph.kotoba-checkpoint-test
             'langgraph.operator-quickstart-test
             'langgraph.superstep-test
             'langgraph.thread-custody-test
             'langgraph.viz-test)
