# langgraph

LangGraph-style graph orchestration in **portable Clojure** — every
namespace is `.cljc`, designed to run on **Clojure-on-WASM hosts**
(SCI, ClojureScript, GraalVM, kotoba-clj) as well as the JVM, with all
state persisted through a **Datomic API**.

Built on [langchain](https://github.com/kotoba-lang/langchain)
(Runnables/LCEL, messages, prompts, models, tools, memory, and the
Datomic-compatible store) — the same layering as upstream
langchain-core / langgraph. langchain is the only dependency, and
it is itself zero-dep.

```
src/langgraph/
  graph.cljc       StateGraph + Pregel superstep loop + interrupts
  checkpoint.cljc  checkpointers (in-memory / Datomic) — resume & time travel
  prebuilt.cljc    create-react-agent
  viz.cljc         graph → Mermaid
```

## Design

- **WASM premise** — no JVM interop, no threads, no wall clock. No
  I/O in the library: HTTP and JSON are *injected host capabilities*
  (see langchain's `anthropic-model`).
- **Datomic API premise** — checkpoints are datoms. Graph execution
  history becomes a queryable fact log — resume, human-in-the-loop,
  time travel, and audits are Datalog queries (ADR-0010 pattern).
  Real Datomic Local or DataScript drops in via the
  `langchain.db/api` function map.
- **Durable loop boundary** — long-running agents are modeled as a host
  supervisor repeatedly running bounded StateGraph ticks. The host owns
  leases, sleep/cadence, crash recovery, budgets, and worker lifecycle;
  the graph owns one auditable step with checkpoint/interrupt support.

## Quickstart

```clojure
;; deps.edn
;; {:deps {io.github.kotoba-lang/langgraph {:git/tag "v0.2.0" :git/sha "…"}}}

(require '[langgraph.graph :as g]
         '[langgraph.prebuilt :as prebuilt]
         '[langgraph.checkpoint :as cp]
         '[langchain.model :as model]
         '[langchain.message :as msg]
         '[langchain.db :as db])

;; --- a graph with reducer channels, conditional edges, interrupts ---
(def graph
  (-> (g/state-graph {:channels {:messages {:reducer into :default []}}})
      (g/add-node :draft (fn [s] {:messages [(msg/ai "draft…")]}))
      (g/add-node :send  (fn [s] {:messages [(msg/ai "sent")]}))
      (g/set-entry-point :draft)
      (g/add-edge :draft :send)
      (g/compile-graph
       {:checkpointer (cp/datomic-checkpointer (db/create-conn cp/checkpoint-schema))
        :interrupt-before #{:send}})))   ; human-in-the-loop

(g/run* graph {:messages [(msg/user "hello")]} {:thread-id "t1"})
;; => {:status :interrupted …}  — review, optionally edit:
(g/update-state! graph "t1" {:messages [(msg/user "approved")]})
(g/run* graph nil {:thread-id "t1"})     ; resume
;; => {:status :done …}

;; --- ReAct agent ---
(def agent
  (prebuilt/create-react-agent
   {:model (model/anthropic-model
            {:api-key API-KEY
             :model "claude-opus-4-8"
             :http-fn host-fetch          ; injected host capability
             :json-write … :json-read …}) ; defaults to js/JSON on cljs
    :tools [{:name "get_weather"
             :description "Get current weather for a location"
             :schema {:type "object"
                      :properties {:location {:type "string"}}
                      :required ["location"]}
             :fn (fn [{:keys [location]}] …)}]}))

(g/invoke agent {:messages [(msg/user "Weather in Paris?")]})
```

Checkpoints are plain datoms, so execution history is queryable:

```clojure
(db/q '[:find ?thread (max ?step)
        :where [?c :checkpoint/thread ?thread]
               [?c :checkpoint/step ?step]]
      (db/db conn))
```

## Mapping from upstream

See [docs/adr/0001-architecture.md](docs/adr/0001-architecture.md) for
the full LangGraph → langgraph correspondence table and the
injected-I/O rationale. The LangChain layer lives in
[langchain](https://github.com/kotoba-lang/langchain).

## Tests / example

```sh
clojure -M:test     # 9 tests, 24 assertions (graph / checkpoint / agent layer)
clojure -Sdeps '{:paths ["src" "examples"]
                 :deps {io.github.kotoba-lang/langchain
                        {:git/tag "v0.1.0" :git/sha "ae475c9"}}}' \
        -M -e "(require 'react-agent) (react-agent/-main)"
```

Workspace development against a local langchain checkout:
`clojure -M:dev:test`.
