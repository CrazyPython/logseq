(ns frontend.db.query-custom
  "Custom queries."
  (:require [datascript.core :as d]
            [frontend.db.utils :as db-utils :refer [date->int]]
            [frontend.db.model :as model]
            [cljs-time.core :as t]
            [frontend.state :as state]
            [clojure.string :as string]
            [cljs.reader :as reader]
            [frontend.extensions.sci :as sci]
            [lambdaisland.glogi :as log]
            [frontend.util :as util]
            [frontend.db.react :as react]
            [frontend.text :as text]
            [clojure.walk :as walk]))

(defn- resolve-input
  [input]
  (cond
    (= :today input)
    (date->int (t/today))
    (= :yesterday input)
    (date->int (t/yesterday))
    (= :tomorrow input)
    (date->int (t/plus (t/today) (t/days 1)))
    (= :current-page input)
    (string/lower-case (state/get-current-page))
    (and (keyword? input)
         (re-find #"^\d+d(-before)?$" (name input)))
    (let [input (name input)
          days (util/parse-int (subs input 0 (dec (count input))))]
      (date->int (t/minus (t/today) (t/days days))))
    (and (keyword? input)
         (re-find #"^\d+d(-after)?$" (name input)))
    (let [input (name input)
          days (util/parse-int (subs input 0 (dec (count input))))]
      (date->int (t/plus (t/today) (t/days days))))

    (and (string? input) (text/page-ref? input))
    (text/page-ref-un-brackets! input)

    :else
    input))

(defn custom-query-result-transform
  [query-result remove-blocks q]
  (let [repo (state/get-current-repo)
        result (db-utils/seq-flatten query-result)
        block? (:block/uuid (first result))]
    (let [result (if block?
                   (let [result (if (seq remove-blocks)
                                  (let [remove-blocks (set remove-blocks)]
                                    (remove (fn [h]
                                              (contains? remove-blocks (:block/uuid h)))
                                            result))
                                  result)]
                     (some->> result
                              (db-utils/with-repo repo)
                              (model/with-block-refs-count repo)
                              (model/sort-blocks)))
                   result)]
      (if-let [result-transform (:result-transform q)]
        (if-let [f (sci/eval-string (pr-str result-transform))]
          (try
            (sci/call-fn f result)
            (catch js/Error e
              (log/error :sci/call-error e)
              result))
          result)
        (if block?
          (db-utils/group-by-page result)
          result)))))

(defn- resolve-query
  [query]
  (let [page-ref? #(and (string? %) (text/page-ref? %))]
    (walk/postwalk
     (fn [f]
       (if (and (list? f)
                (= (first f) '=)
                (= 3 (count f))
                (some page-ref? (rest f)))
         (let [[x y] (rest f)
               [page-ref sym] (if (page-ref? x) [x y] [y x])]
           (list 'contains? sym (text/page-ref-un-brackets! page-ref)))
         f)) query)))

(defn react-query
  [repo {:keys [query inputs] :as query'} query-opts]
  (try
    (let [query (resolve-query query)
          inputs (map resolve-input inputs)
          repo (or repo (state/get-current-repo))
          k [:custom query']]
      (apply react/q repo k query-opts query inputs))
    (catch js/Error e
      (println "Custom query failed: ")
      (js/console.dir e))))

(defn custom-query
  ([query]
   (custom-query query {}))
  ([query query-opts]
   (when-let [query' (cond
                       (and (string? query)
                            (not (string/blank? query)))
                       (reader/read-string query)

                       (map? query)
                       query

                       :else
                       nil)]
     (react-query (state/get-current-repo) query' query-opts))))
