(ns almaz.segment
  "Automatic masks: a layer's mask set to what a segmentation model finds.

   darktable's AI object mask (src/develop/masks/object.c) runs SAM 2.1 on
   points the user clicks; RawTherapee has none. These selections need no
   clicks, so they come from two models that segment the whole image
   (almaz.models):
     :subject  BiRefNet-lite, salient object segmentation: Subject, and
               Background as its inverse
     :scene    UperNet-ConvNeXt on ADE20K, its classes summed into sky,
               person and natural landscape: Sky, Portrait, Landscape
   A model sees the photo after lens correction (masks live in its
   geometry). Its output for a doc is kept, so switching between selections
   from the same model doesn't run it again."
  (:require [almaz.infer.protocol :as p]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(def selections
  "The mask menu: [key label], in order."
  [[:all "All"] [:subject "Subject"] [:background "Background"] [:portrait "Portrait"]
   [:landscape "Landscape"] [:sky "Sky"] [:custom "Custom"]])

(def keys* (mapv first selections))
(def labels (mapv second selections))

(def ^:private sources
  {:subject    {:model :subject :channel 0}
   :background {:model :subject :channel 0 :invert? true}
   :portrait   {:model :scene :channel 1}
   :landscape  {:model :scene :channel 2}
   :sky        {:model :scene :channel 0}})

(defn source
  "Which model output a selection is: {:model :channel :invert?}, or nil
   for :all and :custom (no model)."
  [sel] (get sources sel))

(defn describe
  "What the worker is doing for a selection (\"Finding the subject\")."
  [sel]
  (case sel
    (:subject :background) "Finding the subject"
    :portrait "Finding people"
    :landscape "Finding the landscape"
    :sky "Finding the sky"
    "Masking"))

;; {model {:key [base input-sig] :ptr native floats :n :channels :logits?}}
(defonce ^:private results (atom {}))

(defn- run-model
  "Model `id` over the composite of base + layers: its output as native
   floats (freed by the caller)."
  [id base layers]
  (let [{:keys [backend session spec]} (models/session id)
        {:keys [block channels inputs outputs logits] :as spec} spec
        n block
        in (ffi/alloc (* 4 3 n n))
        out (ffi/alloc (* 4 channels n n))]
    (try
      (px/seg-input! base layers n spec in)
      (p/run-native backend session [{:name (first inputs) :shape [1 3 n n] :ptr in :count (* 3 n n)}]
                    [{:name (first outputs) :ptr out :count (* channels n n)}])
      {:ptr out :n n :channels channels :logits? (boolean logits)}
      (catch Throwable e (ffi/free out) (throw e))
      (finally (ffi/free in)))))

(defn output
  "Model `id`'s output for the composite of base + layers, `key` naming
   that input: run once, then kept until another input replaces it."
  [id key base layers]
  (let [cur (get @results id)]
    (if (= key (:key cur))
      cur
      (let [r (assoc (run-model id base layers) :key key)]
        (swap! results assoc id r)
        (when-let [old (:ptr cur)] (ffi/free old))
        r))))

(defn apply!
  "Set mask `m` to selection `sel` from a model output (output's map)."
  [m sel {:keys [ptr n logits?]}]
  (let [{:keys [channel invert?]} (source sel)]
    (px/mask-set! m (+ ptr (* 4 channel n n)) n n logits?)
    (when invert? (px/mask-invert! m))))

(defn forget!
  "Drop every kept output (their native memory)."
  []
  (doseq [{:keys [ptr]} (vals (first (reset-vals! results {})))] (when ptr (ffi/free ptr))))
