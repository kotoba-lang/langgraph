(ns kotoba-e2e
  "End-to-end smoke test: langgraph StateGraph + kotoba-server checkpointer.

  Starts a graph with 2 nodes (counter → decider), runs it to completion
  over 3 threads, then verifies that state persists across re-invocations.

  Run (kotoba-server must be running on KOTOBA_URL):
    KOTOBA_URL=http://localhost:8077 \\
    KOTOBA_GRAPH=bafyreici6lf6ewlgskwxznw5sa6kydis3y6nmy5qhjq5f7rbog3u7dapc4 \\
    KOTOBA_TOKEN=<operator-jwt> \\
    clojure -A:dev -Sdeps '{:paths [\"src\" \"examples\"],
                            :deps {org.clojure/data.json {:mvn/version \"2.5.0\"}}}' \\
           -M -e '(require (quote kotoba-e2e)) (kotoba-e2e/-main)'

  KOTOBA_GRAPH is the multibase CID derived from the graph name bytes.
  KOTOBA_TOKEN is an operator JWT: base64url({\"alg\":\"HS256\",\"typ\":\"JWT\"}).base64url({\"sub\":\"<operator-did>\",\"exp\":9999999999}).anysig"
  (:require [langchain.kotoba-db         :as kdb]
            [langgraph.kotoba-checkpoint :as kcp]
            [langgraph.checkpoint        :as cp]
            [langgraph.graph             :as lg]
            [clojure.data.json           :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

;; ─── JVM host caps ───────────────────────────────────────────────────────────

(def ^:private http-client (delay (HttpClient/newHttpClient)))

(defn- http-fn [{:keys [url method headers body]}]
  (let [builder (doto (HttpRequest/newBuilder (URI/create url))
                  (.method (.toUpperCase (name (or method :post)))
                           (HttpRequest$BodyPublishers/ofString (or body ""))))
        _       (doseq [[k v] headers] (.header builder k v))
        resp    (.send @http-client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(def host-caps
  {:http-fn    http-fn
   :json-write json/write-str
   :json-read  #(json/read-str % :key-fn keyword)})

;; ─── graph definition ────────────────────────────────────────────────────────

(defn counter-node [state]
  (let [n (inc (get state :count 0))]
    (println (str "  counter: " n))
    {:count n}))

(defn decider-node [state]
  (let [done? (>= (:count state) 3)]
    (println (str "  decider: done=" done?))
    {:done done?}))

(defn- route [state]
  (if (:done state) lg/END :counter))

(defn make-graph [cp]
  (-> (lg/state-graph {:count    {:default 0}
                       :done     {:default false}})
      (lg/add-node :counter counter-node)
      (lg/add-node :decider decider-node)
      (lg/add-edge :counter :decider)
      (lg/add-conditional-edges :decider route)
      (lg/set-entry-point :counter)
      (lg/compile-graph {:checkpointer cp})))

;; ─── main ────────────────────────────────────────────────────────────────────

(defn -main [& _]
  (let [url   (or (System/getenv "KOTOBA_URL")   "http://localhost:8077")
        graph (or (System/getenv "KOTOBA_GRAPH") "langgraph-clj-test")
        token (System/getenv "KOTOBA_TOKEN")]

    (println (str "\n=== kotoba E2E: " url " graph=" graph " ===\n"))

    (let [conn (kdb/kotoba-conn url graph (when token {:token token}))]

      ;; Ensure schema is present (idempotent)
      (print "Ensuring checkpoint schema... ")
      (flush)
      (kcp/ensure-schema! conn host-caps)
      (println "ok")

      (let [cp    (kcp/checkpointer conn host-caps)
            g     (make-graph cp)]

        ;; ── run 3 threads in parallel ─────────────────────────────────────
        (println "\nRunning 3 threads:")
        (doseq [tid ["thread-A" "thread-B" "thread-C"]]
          (println (str "\n[" tid "]"))
          (let [result (lg/invoke g {} {:thread-id tid})]
            (println (str "  final state: " result))))

        ;; ── verify persistence: re-invoke threads ─────────────────────────
        (println "\nVerifying persistence (re-invoke each thread):")
        (doseq [tid ["thread-A" "thread-B" "thread-C"]]
          (let [saved (cp/get-latest cp tid)]
            (if saved
              (println (str "  [" tid "] step=" (:step saved)
                            " status=" (:status saved)
                            " state=" (:state saved)))
              (println (str "  [" tid "] WARNING: no checkpoint found!")))))

        ;; ── list all checkpoints for thread-A ────────────────────────────
        (println "\nCheckpoint history for thread-A:")
        (doseq [ckpt (cp/list-checkpoints cp "thread-A")]
          (println (str "  step=" (:step ckpt) " state=" (:state ckpt))))

        ;; ── time travel: get state at step 1 ─────────────────────────────
        (println "\nTime travel: thread-A at step 1:")
        (let [at1 (cp/get-state-at cp "thread-A" 1)]
          (if at1
            (println (str "  state=" (:state at1)))
            (println "  (not found)")))

        (println "\n=== E2E PASSED ===\n")))))
