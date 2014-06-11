(ns views.db.core
  (:import
   [java.sql SQLException])
  (:require
   [clojure.java.jdbc :as j]
   [clojure.tools.logging :refer [debug]]
   [views.db.honeysql :as vh]
   [views.db.deltas :as vd]
   [views.subscribed-views :refer [subscribed-views broadcast-deltas]]))

;;
;; Takes the HoneySQL template for a view and the arglist
;; and compiles the view with a set of dummy args in the
;; format
;;   [?1, ?2, ?3 ... ?N]
;;
;; Returns a map of the compiled hash-map and the args
;; with keys :dummy-view and :dummy-args respectively.
;;
(defn- compile-dummy-view
  [view-template args]
  (let [dummy-args (take (count args) (range))
        dummy-args (map #(str "?" %) dummy-args)]
    {:dummy-view (apply view-template dummy-args)
     :dummy-args dummy-args}))

;;
;; Terminology and data structures used throughout this code
;;
;; <name>-template - refers to a function which receives parameters
;;                   and returns a HoneySQL hash-map with params interpolated.
;;
;; action          - describes the HoneySQL hash-map for the action to be performed
;;                   --the template function has already been called and returned this
;;                   with the appropriate parameter arguments.
;;
;; view-map        - contains a set of computed information for each view itself.
;;                   Refer to the view-map doc-string for more information.
;;
;; view-check      - SQL for checking whether or not a view needs to receive deltas
;;                   upon completion of an operation.
;;

(defn view-map
  "Constructs a view map from a HoneySQL view function and its arguments.
   Contains four fields:
    :view          - the hash-map with interpolated parameters
    :view-sig      - the \"signature\" for the view, i.e. [:matter 1]
    :args          - the arguments passed in.
    :tables        - the tables present in all :from, :insert-into,
                 :update, :delete-from, :join, :left-join :right-join clauses

   Input is a view template function and a view signature. The template
   function must take the same number of paramters as the signature and
   return a honeysql data structure "
  [view-template view-sig]
  (let [compiled-view (if (> (count view-sig) 1)
                        (apply view-template (rest view-sig))
                        (view-template))]
    (merge {:args          (rest view-sig)
            :view-sig      view-sig
            :view          compiled-view
            :bulk-update?  (:bulk-update? (meta view-template))
            :tables        (set (vh/extract-tables compiled-view))}
           (compile-dummy-view view-template (rest view-sig)))))

(defn view-sig->view-map
  "Takes a map of sig keys to view template function vars (templates)
   and a view signature (view-sig the key for the template map and its args)
   and returns a view-map for that view-sig."
  [templates view-sig]
  (let [lookup (first view-sig)]
    (view-map (get-in templates [lookup :fn]) view-sig)))

(defn do-view-transaction
  "Takes the following arguments:
   schema    - from edl.core/defschema
   db        - clojure.java.jdbc database connection
   all-views - the current set of views (view-maps--see view-map fn docstring for
                  description) in memory for the database
   action    - the HoneySQL pre-SQL hash-map with parameters already interpolated.

   The function will then perform the following sequence of actions, all run
   within a transaction (with isolation serializable):

   1) Create pre-check SQL for each view in the list.
   2) Run the pre-check SQL (or fail out based on some simple heuristics) to
      identify if we want to send delta messages to the view's subscribers
      (Note: this happens after the database action for *inserts only*).
   3) Run the database action (insert/action/delete).
   4) Calculate deltas based on the method described in section 5.4, \"Rule Generation\"
      of the paper \"Deriving Production Rules for Incremental Rule Maintenance\"
      by Stefano Ceri and Jennifer Widom (http://ilpubs.stanford.edu:8090/8/1/1991-4.pdf)

   The function returns the views which received delta updates with the deltas
   keyed to each view-map at the keys :insert-deltas and :delete-deltas."
  [schema db all-views action]
  ;; Every update connected with a view is done in a transaction:
  (j/with-db-transaction [t db :isolation :serializable]
    (let [need-deltas       (vd/do-view-pre-checks t all-views action)
          need-deltas       (map #(vd/generate-view-delta-map % action) need-deltas)
          table             (-> action vh/extract-tables ffirst)
          pkey              (vd/get-primary-key schema table)]
      (vd/perform-action-and-return-deltas schema t need-deltas action table pkey))))

;;
;; Need to catch this and retry:
;; java.sql.SQLException: ERROR: could not serialize access due to concurrent update
;;
(defn get-nested-exceptions*
  [exceptions e]
  (if-let [next-e (.getNextException e)]
    (recur (conj exceptions next-e) next-e)
    exceptions))

(defn get-nested-exceptions
  [e]
  (get-nested-exceptions* [e] e))

(defn do-transaction-fn-with-retries
  [transaction-fn]
  (try
    (transaction-fn)
    (catch SQLException e
      ;; http://www.postgresql.org/docs/9.2/static/errcodes-appendix.html
      (debug "Caught exception with error code: " (.getSQLState e))
      (debug "Exception message: " (.getMessage e))
      ;; (debug "stack trace message: " (.printStackTrace e))
      (if (some #(= (.getSQLState %) "40001") (get-nested-exceptions e))
        (do-transaction-fn-with-retries transaction-fn) ;; try it again
        (throw e))))) ;; otherwise rethrow

(defmacro with-view-transaction
  [subscribed-views binding & forms]
  (let [tvar (first binding)]
    `(if (:deltas ~(second binding)) ;; check if we are in a nested transaction
       (let [~tvar ~(second binding)] ~@forms)
       (do-transaction-fn-with-retries
         (fn []
           (let [deltas# (atom [])
                 result# (j/with-db-transaction [t# ~(second binding) :isolation :serializable]
                                                (let [~tvar (assoc t# :deltas deltas#)]
                                                  ~@forms))]
               (broadcast-deltas ~subscribed-views @deltas#)
               result#))))))

(defn format-deltas
  [views-with-deltas]
  (->> views-with-deltas
       (map #(select-keys % [:view-sig :delete-deltas :insert-deltas]))
       (group-by :view-sig)))

(defn vexec*
  []
  )

(defn vexec!
  "Used to perform arbitrary insert/update/delete actions on the database,
   while ensuring that view deltas are appropriately checked and calculated
   for the currently registered views as reported by a type implementing
   the ISubscribedViews protocol.

   Arguments are:

   - schema: an edl schema (\"(defschema my-schema ...)\")

   - db: a clojure.java.jdbc database

   - action-map: the HoneySQL map for the insert/update/delete action

   - subscribed-views: an implementation of ISubscribedViews implementing
                       the follow functions:

     - subscribed-views takes a ... . It should return
       a collection of view-maps.

     - broadcast-deltas takes a db connection, and the views which have had deltas
       calculate for them and associated with the hash-maps (appropriately
       called views-with-deltas)."
  ([action-map opts]
     (vexec! (:db opts) action-map opts))
  ([db action-map {:keys [schema base-subscribed-views]}]
     (let [subbed-views   (subscribed-views base-subscribed-views db)
           transaction-fn #(do-view-transaction schema db subbed-views action-map)]
       (if-let [deltas (:deltas db)]  ;; inside a transaction we just collect deltas and do not retry
         (let [{:keys [views-with-deltas result-set]} (transaction-fn)]
           (swap! deltas into (format-deltas views-with-deltas))
           result-set)
         (let [{:keys [views-with-deltas result-set]} (do-transaction-fn-with-retries transaction-fn)]
           (broadcast-deltas base-subscribed-views (format-deltas views-with-deltas))
           result-set)))))
