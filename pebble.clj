(ns pebble
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :as repl :refer [doc apropos]]
            [org.httpkit.server :as http]
            [toothpick :refer [ensurer
                               defn-with-closed]])
    (:import [java.net ServerSocket]))

(defonce !state (atom {:hello "pebble"}))
(defonce !changed-at (atom (System/currentTimeMillis)))
(defonce !stop-http (atom nil))
(defonce !socket (atom nil))

(defn now []
  (System/currentTimeMillis))

(defn changed! []
  (reset! !changed-at (now)))

(defn update-at [m path f & args]
  (if (seq path)
    (apply update-in m path f args)
    (apply f m args)))

(defn rename-key [m old-key new-key]
  (-> m
      (assoc new-key (get m old-key))
      (dissoc old-key)))

(defn-with-closed apply-command [{:keys [op path value old-key new-key] :as command}]
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
    :command command
    (str "unknown op: " op)))

(defn handle-line [line]
  (try
    (apply-command (edn/read-string line))
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

(defn-with-closed app [req]
  [get-asset #(slurp (io/file "public" %))
   page (get-asset "page.html")
   css  (get-asset "style.css")
   js   (get-asset "js/script.js")]
  (case (:uri req)
    "/" (response 200 page "text/html; charset=utf-8")
    "/style.css" (response 200 css "text/css; charset=utf-8")
    "/js/script.js" (response 200 js "text/javascript; charset=utf-8")
    "/command" (response 200 (pr-str (handle-line (body-string req))) "application/edn; charset=utf-8")
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
