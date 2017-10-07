(ns tensors.model
  (:import [java.util HashMap Map])
  (:require [schema.core :as s]
            [tensors.core :as tensors]
            [tensors.computation-graph :as cg]
            [tensors.cache-pool :as cache-pool]
            [tensors.model :as model]
            [tensors.node :as node]))

(s/defschema InitParamSpec
  "Spec for how to generate parameter entries independently
  implement `get-param-rng` multi-method for `:distribution`
  for a new distirbution"
  {:distribution s/Keyword
   s/Any s/Any})

(defmulti ^clojure.lang.IFn$ODD get-param-rng :distribution)

(defmethod get-param-rng :uniform
  [{:keys [rand-seed, lower, upper]}]
  (let [lower (double (or lower -1.0))
        upper (double (or upper 1.0))
        r (java.util.Random. (long (or rand-seed 0)))]
    (fn ^double [^longs indices ^double x]
      (+ lower (* (- upper lower) (.nextDouble r))))))

(defmethod get-param-rng :normal
  [{:keys [rand-seed, mean, sigma]}]
  (let [mean (double (or mean 0.0))
        sigma (double (or sigma 1.0))
        r (java.util.Random. (long (or rand-seed 0)))]
    (fn ^double [^longs indices ^double x]
      (/ (+ (.nextGaussian r) mean) sigma))))

(defprotocol PModel
  (tensor-factory [this]
    "return the underlying tensor factory for the model")
  (-add-params! [this param-name shape init-spec]
    "add parameters to the model, returns a param graph node. Some
     argument defaulting happens below so this is the internal method")
  (canonical-node [this param-name]
    "returns a caonical `Node` for the parameter. If parameters
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
   to the underlying data. Don't use it except for an emergency!

  The factory is also adorned with a caching pool under the
  :cache meta-data "
  [factory :- tensors/PFactory]
  (let [m (java.util.HashMap.)
        mk-tensor (fn [shape]
                    #_(println "Cache miss on " shape)
                    (tensors/zeros factory shape))
        factory (with-meta factory {:cache (cache-pool/make 100 mk-tensor)})]
    (with-meta
      (reify

        PModel
        (tensor-factory [this] factory)
        (-add-params! [this param-name shape init-spec]
          (let [param-name (cg/full-node-name param-name)]
            (when-let [existing (.get m param-name)]
              (throw (ex-info "Existing param key" {:existing existing})))
            (let [node (node/map->Node
                        {:type :params
                         :ref-name param-name
                         :value (tensors/zeros factory shape)
                         :grad (tensors/zeros factory shape)
                         :factory factory
                         :shape shape
                         :init init-spec})
                  get-param-val (get-param-rng init-spec)]
              ;; initialize param vals from init-spec
              (tensors/fill! factory (:value node) get-param-val)
              (.put m param-name node)
              node)))
        (canonical-node [this param-name] (.get m param-name))

        clojure.lang.Seqable
        (seq [this]
          (for [e m] [(key e) (val e)])))
      {:data m})))
