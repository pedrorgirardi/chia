(ns chia.view
  (:refer-clojure :exclude [partial])
  (:require [chia.reactive :as r]
            [chia.view.render-loop :as render-loop]
            [chia.view.hiccup :as hiccup]
            [chia.view.hiccup.impl :as hiccup-impl]
            [goog.object :as gobj]
            [chia.view.util :as u]
            ["react-dom" :as react-dom]
            ["react" :as react]
            [clojure.core :as core]
            [chia.util.js-interop :as j])
  (:require-macros [chia.view :as v]))

(def Component react/Component)

(goog-define devtools? false)

;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; API - render loop

(def schedule! render-loop/schedule!)
(def force-update render-loop/force-update)
(def force-update! render-loop/force-update!)
(def flush! render-loop/flush!)

;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Dynamic state

(def ^:dynamic *trigger-state-render* true)
(def ^:dynamic *current-view* nil)

(def instance-counter
  "For tracking the order in which components have been constructed (parent components are always constructed before their children)."
  (volatile! 0))

(defn- wrap-props
  "Wraps :on-change handlers of text inputs to apply changes synchronously."
  [props tag]
  (cond-> props
          (and ^boolean (or (identical? "input" tag)
                            (identical? "textarea" tag))
               (contains? props :on-change)) (update :on-change render-loop/apply-sync!)))

(defn- bind [f]
  (fn []
    (this-as ^js this
      (v/apply-fn f this))))

(def default-methods
  {:view/should-update
   (fn []
     (this-as ^js this
       (let [$state (.-state this)]
         (or (not= (gobj/get $state "props") (gobj/get $state "prev-props"))
             (not= (gobj/get $state "children") (gobj/get $state "prev-children"))
             (when-let [state (gobj/get $state "state")]
               (not= @state (gobj/get $state "prev-state")))))))
   :static/get-derived-state-from-props
   (fn [^js props ^js $state]
     ;; when a component receives new props, update internal state.
     (gobj/set $state "prev-props" (.-props $state))
     (gobj/set $state "props" (.-props props))
     (gobj/set $state "prev-children" (.-children $state))
     (gobj/set $state "children" (.-children props))
     $state)
   :view/will-unmount
   (fn []
     (this-as ^js this
       ;; manually track unmount state, react doesn't do this anymore,
       ;; otherwise our async render loop can't tell if a component is still on the page.
       (gobj/set this "unmounted" true)

       (doseq [f (some-> (.-chia$onUnmount this)
                         (vals))]
         (when f (f this)))
       (some-> (:view/state this) (remove-watch this))

       (r/dispose-reader! this)))
   :view/did-update
   (fn []
     (this-as ^js this
       (let [$state (.-state this)]
         (gobj/set $state "prev-props" (.-props $state))
         (gobj/set $state "prev-children" (.-children $state))
         (when-let [state (.-state $state)]
           (gobj/set $state "prev-state" @state)))))})

(defn wrap-method [k f]
  (case k
    (:view/should-update
     :view/will-receive-state) (bind f)
    :view/initial-state f
    :view/will-unmount
    (fn []
      (this-as ^js this
        (v/apply-fn f this)
        (.call (get default-methods :view/will-unmount) this)))
    :view/render
    (fn []
      (this-as ^js this
        (set! (.-chia$toUpdate this) false)                 ;; for render loop
        (r/with-dependency-tracking! this
                                     (v/apply-fn f this))))
    :view/did-update
    (fn []
      (this-as ^js this
        (v/apply-fn f this)
        (.call (get default-methods :view/did-update) this)))
    :static/get-derived-state-from-props
    (fn [props state]
      (let [default-fn (get default-methods :static/get-derived-state-from-props)]
        (f props (default-fn props state))))

    (:view/will-update
     :view/will-mount
     :view/will-receive-props) (throw (ex-info "Deprecated lifecycle method" {:method k
                                                                              :fn f}))
    (if (fn? f)
      (case (namespace k)
        "view"
        (bind f)
        (fn [& args]
          (this-as this
            (apply f this args))))
      f)))

(defn- wrap-methods
  "Augment lifecycle methods with default behaviour."
  [methods required-keys]
  (assert (set? required-keys))
  (->> (into required-keys (keys methods))
       (reduce (fn [obj k]
                 (doto obj
                   (gobj/set (or (get u/lifecycle-keys k) (throw (ex-info "Unknown lifecycle method" {:k k})))
                             (or (some->> (get methods k)
                                          (wrap-method k))
                                 (get default-methods k))))) #js {})))

(defn- init-state!
  "Bind a component to an IWatchable/IDeref thing."
  [^js this watchable]
  (let [$state (.-state this)]
    (gobj/set $state "state" watchable)
    (gobj/set $state "prev-state" @watchable)

    (add-watch watchable this
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (gobj/set $state "prev-state" old-state)
                   (when-let [^js will-receive (gobj/get this "componentWillReceiveState")]
                     (.call will-receive this))
                   (when (and *trigger-state-render*
                              (if-let [^js should-update (.-shouldComponentUpdate this)]
                                (.call should-update this)
                                true))
                     (force-update this))))))
  watchable)

(defn- init-state-atom!
  "Populate initial state for `component`."
  [^js this ^js $props]
  (when $props
    (when-let [state (when-let [initial-state (.-chia$initialState this)]
                       (let [state-data (if (fn? initial-state)
                                          (apply initial-state (assoc (.-props $props)
                                                                 :view/props (.-props $props))
                                                 (.-children $props))
                                          initial-state)]
                         (atom state-data)))]
      (init-state! this state)))
  this)

(defn- get-state!
  "Lazily create and bind a state atom for `component`"
  [^js this ^js $state]
  (when-not (.-state $state)
    (init-state! this (atom nil)))
  (.-state $state))

(defmulti component-lookup (fn [this k not-found] k))

(defmethod component-lookup :default
  [this k not-found]
  not-found)

(declare class-get)

(extend-type Component
  ;; for convenience, we allow reading keys from a component's props by looking them up
  ;; directly on the component. this enables destructuring in lifecycle/render method arglist.
  ILookup
  (-lookup
    ([^js this k]
     (-lookup this k nil))
    ([^js this k not-found]
     (let [^js $state (.-state this)]
       (if (= "view" (namespace k))
         (case k
           :view/state (get-state! this $state)
           (:view/props
            :view/prev-props
            :view/prev-state
            :view/children
            :view/prev-children) (gobj/get $state (name k) not-found)
           (component-lookup this k not-found))
         (get (.-props $state) k not-found)))))
  r/IReadReactively
  (invalidate! [this _] (force-update this))
  INamed
  (-name [^js this] (.-displayName this))
  (-namespace [this] nil)
  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer (str "👁[" (name this) "]"))))

(defn swap-silently!
  "Swap a component's state atom without forcing an update (render)"
  [& args]
  (binding [*trigger-state-render* false]
    (apply swap! args)))

(defn- get-element-key [props children constructor]
  (or (get props :key)
      (when-let [class-react-key (.-key constructor)]
        (cond (string? class-react-key) class-react-key
              (keyword? class-react-key) (get props class-react-key)
              (fn? class-react-key) (.apply class-react-key (assoc props :view/children children) (to-array children))
              :else (throw (js/Error "Invalid key supplied to component"))))
      (.-displayName constructor)))

(defn- ^:export extend-constructor
  [{:keys [lifecycle-keys
           static-keys

           unqualified-keys
           qualified-keys]} constructor]

  (gobj/extend (.-prototype constructor)
               (.-prototype Component)
               (wrap-methods lifecycle-keys #{:view/should-update
                                              :view/will-unmount
                                              :view/did-update}))

  (doto (.-prototype constructor)
    (j/assoc! "displayName" (.-displayName unqualified-keys))
    (cond-> qualified-keys (j/assoc! "chia$class" qualified-keys)))

  (gobj/extend constructor
               (wrap-methods static-keys #{:static/get-derived-state-from-props})
               unqualified-keys)

  constructor)

(defn- view*
  "Return a React element factory."
  [view-base constructor]
  (let [^js constructor (extend-constructor view-base constructor)]
    (doto (fn [props & children]
            (let [[{:as props
                    :keys [ref]} children] (if (or (map? props)
                                                   (nil? props))
                                             [props children]
                                             [nil (cons props children)])]
              (react/createElement constructor #js {"key" (get-element-key props children constructor)
                                                    "ref" ref
                                                    "props" (cond-> props ref (dissoc :ref))
                                                    "children" children})))
      (gobj/set "chia$constructor" constructor))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Helper functions

(defn class-get
  "Get (qualified) keys from the view's methods map.

   Does not return lifecycle methods"
  ([this k] (class-get this k nil))
  ([^js this k not-found]
   (when this
     (or (some-> (.-chia$constructor this)
                 (.-prototype)
                 (class-get k not-found))
         (-> (gobj/get this "chia$class")
             (get k not-found))))))

(defn pass-props
  "Remove prop keys handled by component, useful for passing down unhandled props to a child component.
  By default, removes all keys listed in the component's :spec/props map. Set `:consume false` for props
  that should be passed through."
  [this]
  (apply dissoc
         (get this :view/props)
         (class-get this :props/consumed)))

(defn combine-props
  "Combines props, merging maps and joining collections/strings."
  [m1 m2]
  (merge-with (fn [a b]
                (cond (string? a) (str a " " b)
                      (coll? a) (into a b)
                      :else b)) m1 m2))

(defn render-to-dom
  "Render view to element, which should be a DOM element or id of element on page."
  [react-element dom-element]
  (react-dom/render react-element (cond->> dom-element
                                           (string? dom-element)
                                           (.getElementById js/document))))

(defn unmount-from-dom
  [dom-element]
  (react-dom/unmountComponentAtNode dom-element))

(defn is-react-element? [x]
  (and x (react/isValidElement x)))

(defn dom-node
  "Return DOM node for component"
  [component]
  (react-dom/findDOMNode component))

(defn mounted?
  "Returns true if component is still mounted to the DOM.
  This is necessary to avoid updating unmounted components."
  [^js component]
  (not (true? (.-unmounted component))))

(defn on-unmount!
  "Register an unmount callback for `component`."
  [^js this key f]
  (set! (.-chia$onUnmount this)
        ((fnil assoc {}) (.-chia$onUnmount this) key f)))

(defn adapt-react-class [the-class]
  (fn [& args]
    (to-array (cons the-class args))))
