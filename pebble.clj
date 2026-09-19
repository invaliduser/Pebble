(ns pebble
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :as repl :refer [doc apropos]]
            [org.httpkit.server :as http]
            [toothpick :refer [ensurer
                               defn-with-closed]])
    (:import [java.net ServerSocket]))

(defonce !state (atom {:hello "pebble"}))
(defonce !stop-http (atom nil))
(defonce !socket (atom nil))

(defn-with-closed apply-command [{:keys [op path value] :as command}]
  [pathv (ensurer vector)]
  (case op
    :ping :pong
    :bye :bye
    :state @!state
    :get (get-in @!state (pathv path))
    :assoc (get-in (swap! !state assoc-in (pathv path) value) (pathv path))
    :reset (reset! !state value)
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

(defn response [status body content-type]
  {:status status
   :headers {"content-type" content-type}
   :body body})


  (defn-with-closed app [req]
  [get-asset #(slurp (io/file "public" %))
   page (get-asset "page.html")
   css  (get-asset "style.css")]
  (case (:uri req)
    "/" (response 200 page "text/html; charset=utf-8")
    "/style.css" (response 200 css "text/css; charset=utf-8")
    "/state" (response 200 (pr-str @!state) "application/edn; charset=utf-8")
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
