(ns views.repl
  (:require
   [honeysql.core :as hsql]
   [edl.core :refer [defschema]]
   [views.core :as vc]
   [views.subscribed-views :as sv]
   [views.fixtures :as vf]
   [clojure.data.generators :as dg]
   [views.db.core :as vdb]
   [clj-logging-config.log4j :refer [set-logger! set-loggers!]]))

(defn rand-str
  ([] (rand-str 10))
  ([n] (dg/string #(rand-nth (clojure.string/split "abcdefghijklmnopqrstuvwxyz" #"\B")) n)))

(defschema test-schema vf/db "public")

(def user-insert (hsql/build :insert-into :users :values [{:name (rand-str) :created_on (vf/sql-ts)}]))

(defn make-opts
  ([] (make-opts vf/templates))
  ([templates] (vc/config {:db vf/db :schema test-schema :templates templates :unsafe? true})))

(defn test-subscribe
  ([sk views] (test-subscribe sk views (make-opts)))
  ([sk views opts]
     (sv/subscribe-views (:base-subscribed-views opts) {:subscriber-key sk :views [[:users]]})))

(comment
  (require '[clj-logging-config.log4j :as lc] '[views.repl :as vr] '[views.db.core :as vdb] :reload)
  (lc/set-loggers! "views.base-subscribed-views" {:level :info})
  (def opts (vr/make-opts))
  (vr/test-subscribe 1 [[:users]])
  (vdb/vexec! vr/user-insert opts)
  (vr/test-subscribe 2 [[:users]])
  (vdb/vexec! vr/user-insert opts)
  )