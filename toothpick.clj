(ns toothpick
  (:require [clojure.walk :refer [postwalk]]))

;; (ensure path vector? [path])
;; => path, if it is already a vector
;; => [path], otherwise
;; The expression is evaluated once, then reused in the fallback form.
(defmacro ensure [expr pred fallback-exp]
  (let [x (gensym "x")]
    `(let [~x ~expr]
       (if (~pred ~x)
         ~x
           ~(postwalk #(if (= % expr)
                         x
                         %)
                      fallback-exp)))))


(defmacro defmacro-with-closed [name args bindings & body]
  `(let ~bindings
     (defmacro ~name ~args
       ~@body)))

;; (ensure-by vector path)
;; => path, if it is already a vector
;; => (vector path), otherwise
;;
;; (ensure-by vec xs)
;; => xs, if it is already a vector
;; => (vec xs), otherwise

(defmacro-with-closed ensure-by [f expr]
  [result-pred '{list list?
                 set set?
                 str string?
                 symbol symbol?
                 keyword keyword?
                 vec vector?
                 vector vector?}]
  (let [pred (or (result-pred f)
                 (throw (ex-info "ensure-by does not know this operation"
                                 {:operation f
                                  :known (keys result-pred)})))]
    `(let [x# ~expr]
       (if (~pred x#) x# (~f x#)))))

;; (def pathv (ensurer vector))
;; (pathv :a)      => [:a]
;; (pathv [:a :b]) => [:a :b]
(defmacro ensurer [f]
  `(fn [x#] (ensure-by ~f x#)))


;; (defn-with-closed path-command [command]
;;   [pathv #(if (vector? %) % [%])]
;;   ...)
;; On the first call, creates the closed-over helper bindings and replaces the
;; var root with a smaller function that keeps those helpers in its closure.
;; Bindings used only to construct later bindings do not need special cleanup:
;; if the final function body does not mention them, the closure does not keep
;; them. This makes the binding vector a handy little build site:
;;   [get-asset #(slurp ...)
;;    page (get-asset "index.html")]
;; `page` stays if the body uses it; `get-asset` can fall away.
(defmacro defn-with-closed [name args bindings & body]
  (let [argv (vec (repeatedly (count args) gensym))]
    `(defn ~name ~argv
       (let ~bindings
         (let [f# (fn ~args ~@body)]
           (alter-var-root (var ~name) (constantly f#))
           (f# ~@argv))))))
