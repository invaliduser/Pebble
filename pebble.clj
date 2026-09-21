(ns pebble
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :as repl :refer [doc apropos]]
            [org.httpkit.server :as http]
            [toothpick :refer [ensurer
                               defn-with-closed]])
    (:import [java.io PushbackReader StringReader]
             [java.net ServerSocket]))

(def state-file ".pebble-state.edn")
(def log-file ".pebble-log.edn")
(def default-state {:hello "pebble"})

(defn load-state []
  (let [f (io/file state-file)]
    (if (.exists f)
      (edn/read-string (slurp f))
      default-state)))

(defonce !state (atom (load-state)))
(defonce !changed-at (atom (System/currentTimeMillis)))
(defonce !stop-http (atom nil))
(defonce !socket (atom nil))

(defn now []
  (System/currentTimeMillis))

(defn save! []
  (spit state-file (pr-str @!state)))

(defn changed! []
  (save!)
  (reset! !changed-at (now)))

(defn mutates? [{:keys [op]}]
  (contains? #{:assoc :reset :rename-key :dissoc :eval :undo :redo} op))

(defn log! [command before result]
  (spit log-file
        (str (pr-str (cond-> {:at (now) :command command :result result}
                       before (assoc :before before)))
             "\n")
        :append true))

(defn log-events []
  (let [f (io/file log-file)]
    (if (.exists f)
      (mapv edn/read-string (line-seq (io/reader f)))
      [])))

(defn update-at [m path f & args]
  (if (seq path)
    (apply update-in m path f args)
    (apply f m args)))

(defn before [{:keys [op path key old-key new-key]}]
  (case op
    :assoc (let [parent-path (pop path)
                 k (peek path)
                 parent (get-in @!state parent-path)]
             {:op op :path path :existed? (contains? parent k) :old (get parent k)})
    :reset {:op op :state @!state}
    :rename-key {:op op :path path :parent (get-in @!state path)}
    :dissoc (let [parent (get-in @!state path)]
              {:op op :path path :key key :existed? (contains? parent key) :old (get parent key)})
    :eval nil
    nil))

(defn restore! [{:keys [op path key existed? old state parent]}]
  (case op
    :assoc (let [parent-path (pop path)
                 k (peek path)]
             (if existed?
               (swap! !state assoc-in path old)
               (swap! !state update-at parent-path dissoc k)))
    :reset (reset! !state state)
    :rename-key (swap! !state update-at path (constantly parent))
    :dissoc (when existed?
              (swap! !state assoc-in (conj path key) old))
    :eval (reset! !state state)))

(declare apply-command)

(defn latest-undoable []
  (let [events (log-events)
        undone (reduce (fn [xs {:keys [command result]}]
                         (case (:op command)
                           :undo (if-let [i (:undid-index result)] (conj xs i) xs)
                           :redo (if-let [i (:redid-index result)] (disj xs i) xs)
                           xs))
                       #{}
                       events)]
    (first
     (for [[i event] (rseq (vec (map-indexed vector events)))
           :when (and (:before event)
                      (not (undone i))
                      (not= :undo (get-in event [:command :op])))]
       [i event]))))

(defn edit? [{:keys [command before]}]
  (and before
       (not (contains? #{:undo :redo} (:op command)))))

(defn latest-redoable []
  (let [events (log-events)
        last-edit (last (keep-indexed #(when (edit? %2) %1) events))
        suffix (subvec events (inc (or last-edit -1)))
        redone (set (keep-indexed
                     (fn [_ {:keys [command result]}]
                       (when (= :redo (:op command))
                         (:redid-undo-index result)))
                     suffix))]
    (first
     (for [[offset {:keys [command result] :as event}] (rseq (vec (map-indexed vector suffix)))
           :let [i (+ (inc (or last-edit -1)) offset)]
           :when (and (= :undo (:op command))
                      (:undid-index result)
                      (not (redone i)))]
       [i event]))))

(defn redo! [[undo-index {{target-index :undid-index} :result}]]
  (let [event (nth (log-events) target-index)
        result (apply-command (:command event))]
    {:redid-undo-index undo-index
     :redid-index target-index
     :redid (:command event)
     :result result}))

(defn rename-key [m old-key new-key]
  (-> m
      (assoc new-key (get m old-key))
      (dissoc old-key)))

(defn eval-code [code]
  (binding [*ns* (the-ns 'pebble)]
    (let [eof (Object.)
          rdr (PushbackReader. (StringReader. code))]
      (loop [result nil]
        (let [form (read rdr false eof)]
          (if (identical? eof form)
            result
            (recur (eval form))))))))

(defn-with-closed apply-command [{:keys [op path value old-key new-key key code] :as command}]
  [pathv (ensurer vector)]
  (case op
    :ping :pong
    :bye :bye
    :state @!state
    :get (get-in @!state (pathv path))
    :assoc (let [path (pathv path)
                 state (swap! !state assoc-in path value)]
             (changed!)
             (get-in state path))
    :reset (let [state (reset! !state value)]
             (changed!)
             state)
    :rename-key (let [path (pathv path)
                      state (swap! !state update-at path rename-key old-key new-key)]
                  (changed!)
                  (get-in state (conj path new-key)))
    :dissoc (let [path (pathv path)
                  state (swap! !state update-at path dissoc key)]
              (changed!)
              (get-in state path))
    :undo (if-let [[i event] (latest-undoable)]
            (do
              (restore! (:before event))
              (changed!)
              {:undid-index i :undid (:command event)})
            {:undid nil})
    :redo (if-let [undo-event (latest-redoable)]
            (redo! undo-event)
            {:redid nil})
    :eval (let [result (eval-code code)]
            (save!)
            result)
    :command command
    (str "unknown op: " op)))

(defn handle-line [line]
  (try
    (let [command (edn/read-string line)
          before (before command)
          result (apply-command command)]
      (when (and (mutates? command)
                 (case (:op command)
                   :undo (:undid-index result)
                   :redo (:redid-index result)
                   true))
        (log! command before result))
      result)
    (catch Throwable e
      (.getMessage e))))

(defn serve-client [socket]
  (future
    (with-open [s socket
                rdr (io/reader s)
                w (io/writer s)]
      (loop []
        (when-let [line (.readLine rdr)]
          (let [result (handle-line line)]
            (.write w (pr-str result))
            (.write w "\n")
            (.flush w)
            (when-not (= :bye result)
              (recur))))))))

(defn start-socket! [port]
  (let [server (ServerSocket. port)]
    (reset! !socket server)
    (future
      (while (not (.isClosed server))
        (try
          (serve-client (.accept server))
          (catch Throwable _))))
    server))

(defn response
  ([status body content-type]
   (response status body content-type nil))
  ([status body content-type headers]
   {:status status
    :headers (merge {"content-type" content-type} headers)
    :body body}))

(defn body-string [req]
  (slurp (:body req)))

(defn whoami []
  (let [repo (.getCanonicalPath (io/file "."))]
    {:name "pebble"
     :kind :local-shared-state
     :description "A local shared state object for Daniel and Codex"
     :repository-location repo
     :routes ["/" "/state" "/command" "/whoami"]
     :agent-interface {:helper "./agent"
                       :agents-md-location (str repo "/AGENTS.md")
                       :socket {:protocol "newline-delimited EDN command maps"
                                :host "localhost"
                                :env-port "PEBBLE_SOCKET_PORT"
                                :default-port 7778}}}))

(defn get-asset [path]
  (slurp (io/file "public" path)))

(defn app [req]
  (case (:uri req)
    "/" (response 200 (get-asset "page.html") "text/html; charset=utf-8")
    "/style.css" (response 200 (get-asset "style.css") "text/css; charset=utf-8")
    "/js/script.js" (response 200 (get-asset "js/script.js") "text/javascript; charset=utf-8")
    "/command" (response 200 (pr-str (handle-line (body-string req))) "application/edn; charset=utf-8")
    "/whoami" (response 200 (pr-str (whoami)) "application/edn; charset=utf-8")
    "/state" (response 200
                        (pr-str @!state)
                        "application/edn; charset=utf-8"
                        {"x-pebble-changed-at" (str @!changed-at)})
    (response 404 "not found\n" "text/plain; charset=utf-8")))

(defn start-http! [port]
  (reset! !stop-http (http/run-server app {:port port})))

(defn env-int [k default]
  (if-let [v (System/getenv k)]
    (parse-long v)
    default))

(defn -main [& _]
  (let [http-port (env-int "PEBBLE_HTTP_PORT" 7777)
        socket-port (env-int "PEBBLE_SOCKET_PORT" 7778)]
    (start-http! http-port)
    (start-socket! socket-port)
    (println "pebble")
    (println "  http:  " (str "http://localhost:" http-port))
    (println "  socket:" (str "localhost:" socket-port))
    @(promise)))
