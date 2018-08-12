(ns chia.view.hiccup
  (:require [chia.view.hiccup.impl :as hiccup]
            ["react" :as react]))

(enable-console-print!)
(set! *warn-on-infer* true)

;; patch IPrintWithWriter to print javascript symbols without throwing errors
(when (exists? js/Symbol)
  (extend-protocol IPrintWithWriter
    js/Symbol
    (-pr-writer [sym writer _]
      (-write writer (str "\"" (.toString sym) "\"")))))

(defprotocol IElement
  (to-element [this] "Returns a React element representing `this`"))

(defprotocol IEmitHiccup
  (to-hiccup [this] "Returns a hiccup form representing `this`"))

(defn format-props [clj-props tag]
  (assert (keyword? tag)
          "The first element in a vector must be a keyword.")
  (let [[_ tag id classes] (hiccup/parse-key-memoized tag)]
    [tag (hiccup/props->js tag id classes clj-props)]))

(defn make-fragment [children]
  (.apply hiccup/*create-element* nil #js [hiccup/*fragment* #js {"children" children}]))

(defn -to-element [form]
  (when form
    (cond (vector? form)
          (let [tag (form 0)]
            (cond (keyword? tag)
                  (let [[props children] (hiccup/parse-args form)
                        [js-tag js-props] (format-props props (form 0))
                        args (hiccup/reduce-flatten-seqs -to-element [js-tag js-props] conj children)]
                    (apply hiccup/*create-element* args))
                  (fn? tag) (-to-element (apply tag (rest form)))
                  :else (make-fragment (mapv -to-element form))))

          (satisfies? IElement form)
          (to-element form)

          (satisfies? IEmitHiccup form)
          (-to-element (to-hiccup form))

          (seq? form)
          (.apply hiccup/*create-element* nil
                  (reduce (fn [^js arr el]
                            (doto arr
                              (.push (-to-element el))))
                          #js [hiccup/*fragment* nil] form))

          (= js/Array (.-constructor form))
          (if (fn? (first form))
            (.apply hiccup/*create-element* nil (hiccup/clj->js-args! form -to-element))
            (make-fragment form))

          :else form)))

(defn element
  "Converts Hiccup form into a React element. If a non-vector form
   is supplied, it is returned untouched. Attribute and style keys
   are converted from `dashed-names` to `camelCase` as spec'd by React.

   - optional -
   :wrap-props (fn) is applied to all props maps during parsing.
   :create-element (fn) overrides React.createElement."
  ([form]
   (-to-element form))
  ([form {:keys [wrap-props
                 create-element]}]
   (binding [hiccup/*wrap-props* wrap-props
             hiccup/*create-element* (or hiccup/*create-element* create-element)]
     (-to-element form))))