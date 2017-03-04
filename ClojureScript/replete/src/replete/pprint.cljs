(ns replete.pprint
  (:refer-clojure :exclude [lift-ns])
  (:require [fipp.visit :refer [visit visit*]]
            [fipp.engine :refer (pprint-document)]))

(defn pretty-coll [{:keys [print-level print-length] :as printer}
                   open xs sep close f]
  (let [printer  (cond-> printer print-level (update :print-level dec))
        xform    (comp (if print-length (take print-length) identity)
                   (map #(f printer %))
                   (interpose sep))
        ys       (if (pos? (or print-level 1))
                   (sequence xform xs)
                   "#")
        ellipsis (when (and print-length (seq (drop print-length xs)))
                   [:span sep "..."])]
    [:group open [:align ys ellipsis] close]))

(defn- lift-ns
  "Returns [lifted-ns lifted-map] or nil if m can't be lifted."
  [print-namespace-maps m]
  (when print-namespace-maps
    (loop [ns nil
           [[k v :as entry] & entries] (seq m)
           lm (empty m)]
      (if entry
        (when (or (keyword? k) (symbol? k))
          (if ns
            (when (= ns (namespace k))
              (recur ns entries (assoc lm (strip-ns k) v)))
            (when-let [new-ns (namespace k)]
              (recur new-ns entries (assoc lm (strip-ns k) v)))))
        [ns lm]))))

(def csi
  "The control sequence initiator: `ESC [`"
  "\u001b[")

(def default-theme
  {:results-font         (str csi "34m")
   :results-string-font  (str csi "32m")
   :results-keyword-font (str csi "35m")
   :reset-font           (str csi "30m")})

(defn wrap-theme
  [kw theme text]
  [:span [:pass (kw theme)] [:text text] [:pass (:reset-font theme)]])

(defrecord RepletePrinter [symbols print-meta print-length print-level print-namespace-maps theme]

  fipp.visit/IVisitor

  (visit-unknown [this x]
    [:text (pr-str x)])


  (visit-nil [this]
    (wrap-theme :results-font theme "nil"))

  (visit-boolean [this x]
    (wrap-theme :results-font theme (str x)))

  (visit-string [this x]
    (wrap-theme :results-string-font theme (pr-str x)))

  (visit-character [this x]
    (wrap-theme :results-string-font theme (pr-str x)))

  (visit-symbol [this x]
    [:text (str x)])

  (visit-keyword [this x]
    (wrap-theme :results-keyword-font theme (pr-str x)))

  (visit-number [this x]
    (wrap-theme :results-font theme (pr-str x)))

  (visit-seq [this x]
    (if-let [pretty (symbols (first x))]
      (pretty this x)
      (pretty-coll this "(" x :line ")" visit)))

  (visit-vector [this x]
    (pretty-coll this "[" x :line "]" visit))

  (visit-map [this x]
    (let [[ns lift-map] (lift-ns print-namespace-maps x)
          prefix (when (some? ns)
                   (str "#:" ns))]
      (pretty-coll this (str prefix "{") (or lift-map x) [:span "," :line] "}"
        (fn [printer [k v]]
          [:span (visit printer k) " " (visit printer v)]))))

  (visit-set [this x]
    (pretty-coll this "#{" x :line "}" visit))

  (visit-tagged [this {:keys [tag form]}]
    [:group "#" (pr-str tag)
     (when (or (and print-meta (meta form))
               (not (coll? form)))
       " ")
     (visit this form)])


  (visit-meta [this m x]
    (if print-meta
      [:align [:span "^" (visit this m)] :line (visit* this x)]
      (visit* this x)))

  (visit-var [this x]
    [:text (pr-str x)])

  (visit-pattern [this x]
    [:text (pr-str x)])

  (visit-record [this x]
    [:text (pr-str x)]))

(defn pprint
  ([x] (pprint x {}))
  ([x options]
   (let [defaults {:symbols {}
                   :print-length *print-length*
                   :print-level *print-level*
                   :print-meta *print-meta*
                   :print-namespace-maps *print-namespace-maps*
                   :theme default-theme}
         full-opts (merge defaults options)
         printer (map->RepletePrinter full-opts)]
     (pprint-document (visit printer x) full-opts))))
