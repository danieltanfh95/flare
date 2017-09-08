(ns tensors.model
  (:import [java.util HashMap Map])
  (:require [schema.core :as s]
            [tensors.core :as tensors]
            [tensors.computation-graph :as cg]))

(s/defschema InitParamSpec
  "Spec for how to generate parameter entries independently
  implement `get-param-rng` multi-method for `:distribution`
  for a new distirbution"
  {:distribution s/Keyword
   s/Any s/Any})

(defmulti ^clojure.lang.IFn$D get-param-rng :distribution)

(s/defschema ParamsNode
  "Params graph node schema"
  (assoc cg/Node
         :init InitParamSpec
         :type (s/eq :params)))

(defmethod get-param-rng :uniform
  [{:keys [rand-seed, lower, upper]}]
  (let [lower (double (or lower -1.0))
        upper (double (or upper 1.0))
        r (java.util.Random. (long (or rand-seed 0)))]
    (fn ^double []
      (+ lower (* (- upper lower) (.nextDouble r))))))

(defmethod get-param-rng :normal
  [{:keys [rand-seed, mean, sigma]}]
  (let [mean (double (or mean 0.0))
        sigma (double (or sigma 1.0))
        r (java.util.Random. (long (or rand-seed 0)))]
    (fn ^double []
      (/ (+ (.nextGaussian r) mean) sigma))))

(s/defn init-params
  [shape :- tensors/Shape
   get-param :- clojure.lang.IFn$D]
  (for [_ (range (first shape))]
    (if (= 1 (count shape))
      (get-param)
      (init-params (drop 1 shape) get-param))))

(defprotocol PModel
  (-add-params! [this param-name shape init-spec]
    "add parameters to the model, returns a param graph node. Some
     argument defaulting happens below so this is the internal method")
  (init! [this tensor-factory]
    "takes a `tensors/PFactory` and populates initial values
     and zeros out gradients for all parameters")
  (canonical-node [this param-name]
    "returns a caonical `ParamNode` for the parameter. If parameters
     have been initialized, also returns `:value` and `:grad` tensor fields"))

(defn add-params!
  [model shape & {:keys [name, init]}]
  (let [name (or name (clojure.core/name (gensym "param")))
        init (or init {:distribution :uniform})]
    (s/validate s/Str name)
    (s/validate InitParamSpec init)
    (-add-params! model name shape init)))


(s/defn simple-param-collection :- PModel
  "Simple collection of parameters
   NOTE: The meta-data of the param-collection gives you access
   to the underlying data. Don't use it except for an emergency!"
  []
  (let [m (java.util.HashMap.)]
    (with-meta
      (reify

        PModel
        (-add-params! [this param-name shape init-spec]
          (let [param-name (cg/full-node-name param-name)]
            (when-let [existing (.get m param-name)]
              (throw (ex-info "Existing param key" {:existing existing})))
            (let [node {:type :params
                        :ref-name param-name
                        :shape shape
                        :init init-spec}]
              (.put m param-name node)
              node)))
        (canonical-node [this param-name] (.get m param-name))
        (init! [this factory]
          (let [ks (keys m)]
            (doseq [^java.util.Map$Entry e m]
              (let [params (.getValue e)
                    get-param-val (get-param-rng (:init params))
                    ;; TODO: Refactor API to allow natively populating tensor
                    ;; rather than first doing Clojure data structures to Tensor
                    init-vals (init-params (:shape params) get-param-val)
                    ;; bake values into tensor
                    value (tensors/from-nums factory init-vals)
                    grad (tensors/zeros factory (:shape params))]
                (.setValue e (assoc params :value value :grad grad))))))

        clojure.lang.Seqable
        (seq [this]
          (for [e m] [(key e) (val e)])))
      {:data m})))