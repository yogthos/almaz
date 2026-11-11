(ns almaz.doc
  "The document model, held in a relatum database (passed in, returned new).

   A document is one image; its filter layers reference it (deleting the
   document deletes them). There is at most one layer of each kind, at a
   fixed place in the pipeline (`kinds`), created the first time it is
   switched on and switched off rather than removed. Each layer runs its
   filter over the composite of the layers below it and blends its result
   back over that composite through its mask and opacity, the way photo
   editors stack adjustment layers:

     composite_0 = base
     composite_i = composite_{i-1} + (filter_i(composite_{i-1}) - composite_{i-1})
                                     * opacity_i * mask_i

   Native buffers (the decoded image, the preview, the thumbnail, each layer's
   filter output and mask) are stored as pointer columns; almaz.lifecycle owns
   them.

   Which layers need (re)processing is derived, not tracked: a layer's
   signature is its kind and params plus the signature of the composite it
   reads, and a layer is stale when its :done-sig differs. Editing a layer's
   params invalidates it and everything above; its opacity, mask or feather
   only change its contribution, so they invalidate the layers above but not
   the layer itself."
  (:require [clojure.string :as str]
            [almaz.color :as c]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(def statuses #{:pending :loading :thumb :ready :error})

(s/create-table :doc
  [[:id          :int     :primary :key :auto]
   [:path        :text    :unique]
   [:name        :text]
   [:raw         :bool]
   [:position    :int]                      ; filmstrip order
   [:status      :keyword :check [contains? #{:pending :loading :thumb :ready :error} :status]]
   [:error       :text    :nullable]
   [:w           :int     :nullable]
   [:h           :int     :nullable]
   [:edited-at   :int     :default 0]       ; ms; processing waits for edits to settle
   [:autopiloted :bool    :default false]
   [:noise       :real    :nullable]        ; estimates (almaz.autopilot)
   [:sharpness   :real    :nullable]
   [:base        :int     :nullable]        ; native handles
   [:preview     :int     :nullable]
   [:thumb       :int     :nullable]
   [:thumb-rev   :int     :default 0]
   [:thumb-sig   :text    :nullable]        ; output-signature the thumbnail shows
   ;; geometry (almaz.geom): orientation and the kept rect, source px
   [:orient      :int     :default 0 :check [:and [>= :orient 0] [<= :orient 7]]]
   [:crop-x      :int     :nullable]
   [:crop-y      :int     :nullable]
   [:crop-w      :int     :nullable]
   [:crop-h      :int     :nullable]
   ;; the crop's constraint (almaz.crop) and the custom aspect ratio
   [:constrain   :keyword :default :free]
   [:ratio-w     :real    :default 3.0 :check [> :ratio-w 0.0]]
   [:ratio-h     :real    :default 2.0 :check [> :ratio-h 0.0]]])

(def tone-keys [:exposure :contrast :highlights :shadows :whites :blacks :saturation])

(defn- colour-params [kind] (assoc (c/defaults kind) :model :classic))

(def kinds
  "Layer kinds, in pipeline order (denoise before sharpen: sharpening noise
   amplifies it; repairs over the restored image; the colour adjustments
   after them,
   in darktable's order: tone, levels, colour balance, hue and saturation,
   selective colour, black & white, then the vignette over the result).
   :tab is where its panel lives, :params its settings' defaults, :opacity
   the strength it starts at when switched on by hand (else full). A :live
   kind is computed as it is drawn (no processing, no pixels of its own)."
  {:denoise   {:label "Denoise" :order 0 :tab :enhance :opacity 0.5
               :params {:model :ai :detail 0.0 :amount 1.0 :radius 1.5}}
   :sharpen   {:label "Sharpen" :order 1 :tab :enhance :opacity 0.5
               :params {:model :ai :detail 0.0 :amount 1.0 :radius 1.5}}
   ;; the Repair brush: its mask is what to repair (empty to start), filled
   ;; from the surroundings (almaz.engine/repair!)
   :repair    {:label "Repair" :order 2 :tab :enhance :mask-input true :mask-fill 0.0 :feather 3.0
               :params {:model :classic}}
   :light     {:label "Light" :order 3 :tab :color :live true
               :params (assoc (zipmap tone-keys (repeat 0.0)) :model :classic)}
   :levels    {:label "Levels" :order 4 :tab :color :live true :params (colour-params :levels)}
   :balance   {:label "Color Balance" :order 5 :tab :color :live true :params (colour-params :balance)}
   :hsl       {:label "Hue & Saturation" :order 6 :tab :color :live true :params (colour-params :hsl)}
   :selective {:label "Selective Color" :order 7 :tab :color :live true :params (colour-params :selective)}
   :bw        {:label "Black & White" :order 8 :tab :color :live true :params (colour-params :bw)}
   :vignette  {:label "Vignette" :order 9 :tab :color :live true :params (colour-params :vignette)}})

(defn default-opacity
  "The strength a `kind` layer starts at: full strength is too much for the
   AI filters, which are usually wanted partly blended."
  [kind]
  (get-in kinds [kind :opacity] 1.0))

(defn kinds-in
  "The kinds whose panels live in `tab`, in pipeline order."
  [tab]
  (->> kinds (filter #(= tab (:tab (val %)))) (sort-by (comp :order val)) (mapv key)))

(def ^:private colour-columns
  "One real column per colour setting, its range checked."
  (for [[_ m] (sort-by key c/settings)
        [k [d lo hi]] (sort-by key m)]
    [k :real :default d :check [:and [>= k lo] [<= k hi]]]))

(s/create-table* :layer
  (into
   [[:id          :int     :primary :key :auto]
    [:doc-id      :int     :references :doc :on-delete :cascade]
    [:position    :int]                      ; stack order (the kind's), bottom first
    [:kind        :keyword :check [contains? (set (keys kinds)) :kind]]
    [:enabled     :bool    :default true]
    [:opacity     :real    :default 1.0 :check [:and [>= :opacity 0.0] [<= :opacity 1.0]]]
    [:model       :keyword :check [contains? #{:ai :classic} :model]]
    [:detail      :real    :default 0.0]
    [:amount      :real    :default 1.0]
    [:radius      :real    :default 1.5]
    ;; light adjustments (exposure in EV; the rest -1..1, 0 neutral)
    [:exposure    :real    :default 0.0 :check [:and [>= :exposure -5.0] [<= :exposure 5.0]]]
    [:contrast    :real    :default 0.0 :check [:and [>= :contrast -1.0] [<= :contrast 1.0]]]
    [:highlights  :real    :default 0.0 :check [:and [>= :highlights -1.0] [<= :highlights 1.0]]]
    [:shadows     :real    :default 0.0 :check [:and [>= :shadows -1.0] [<= :shadows 1.0]]]
    [:whites      :real    :default 0.0 :check [:and [>= :whites -1.0] [<= :whites 1.0]]]
    [:blacks      :real    :default 0.0 :check [:and [>= :blacks -1.0] [<= :blacks 1.0]]]
    [:saturation  :real    :default 0.0 :check [:and [>= :saturation -1.0] [<= :saturation 1.0]]]
    [:feather     :real    :default 24.0]
    [:mask-rev    :int     :default 0]
    [:done-sig    :text    :nullable]        ; the signature its output matches
    [:partial-rev :int     :nullable]        ; mask-rev of a run that skipped tiles
    [:img         :int     :nullable]        ; native handles
    [:mask        :int     :nullable]]
   colour-columns)
  ;; one layer per position, and one of each kind, in a document's stack
  :unique [[:doc-id :position] [:doc-id :kind]])

;; the source photo's shooting metadata, carried into exports
(s/create-table :exif
  [[:doc-id      :int     :primary :key :references :doc :on-delete :cascade]
   [:make        :text    :nullable]
   [:model       :text    :nullable]
   [:lens        :text    :nullable]
   [:taken       :text    :nullable]       ; EXIF "YYYY:MM:DD HH:MM:SS"
   [:exposure    :real    :nullable]       ; seconds
   [:fnumber     :real    :nullable]
   [:focal       :real    :nullable]       ; mm
   [:iso         :int     :nullable]
   [:focal35     :int     :nullable]
   [:orientation :int     :nullable]])

;; a document's layers in stack order (rc/children)
(s/create-index :layer [:doc-id :position] :type :btree)

(defn params
  "A layer's settings (its kind's parameters)."
  [layer]
  (select-keys layer (keys (get-in kinds [(:kind layer) :params]))))

(defn live? "Is the layer computed as it's drawn?" [layer] (boolean (get-in kinds [(:kind layer) :live])))

(defn mask-input?
  "Is the layer's mask what it works on (its output depends on the mask),
   rather than only where its result shows?"
  [layer] (boolean (get-in kinds [(:kind layer) :mask-input])))

(defn mask-fill "A new mask's value for the layer: 1 everywhere, 0 for repairs." [layer]
  (get-in kinds [(:kind layer) :mask-fill] 1.0))

(defn tone
  "A light layer's adjustments, or nil for other kinds."
  [layer]
  (when (= :light (:kind layer)) (select-keys layer tone-keys)))

(defn colour
  "A colour adjustment layer's [kind settings] (almaz.color), or nil."
  [layer]
  (when (contains? c/settings (:kind layer))
    [(:kind layer) (select-keys layer (keys (c/settings (:kind layer))))]))

(defn empty-db [] (rc/create-db))

;; ---- documents ---------------------------------------------------------------
(defn doc [db id] (rc/row db :doc id))

(defn docs
  "Every document, in filmstrip order."
  [db]
  (rc/q db [[:from :doc]] {:sort [:position]}))

(defn add-doc
  "Add an image file; its id is assigned (rc/next-id tells which)."
  [db {:keys [path raw?]}]
  (rc/insert db :doc {:path path :name (last (str/split path #"/"))
                      :raw (boolean raw?) :status :pending
                      :position (count (rc/rows db :doc))}))

(defn update-doc [db id f & args]
  (apply rc/update db :doc [= :id id] f args))

(defn remove-doc
  "Remove a document; its layers go with it."
  [db id]
  (rc/delete db :doc [= :id id]))

;; ---- layers ------------------------------------------------------------------
(defn layer [db id] (rc/row db :layer id))

(defn layers
  "A document's layers, bottom of the stack first."
  [db doc-id]
  (rc/children db :layer/by-doc-id+position doc-id))

(defn layer-index [db id]
  (when-let [l (layer db id)]
    (first (keep-indexed (fn [i x] (when (= id (:id x)) i)) (layers db (:doc-id l))))))

(defn layer-of
  "Doc `doc-id`'s layer of `kind`, or nil."
  [db doc-id kind]
  (first (filter #(= kind (:kind %)) (layers db doc-id))))

(defn ensure-layer
  "Doc `doc-id`'s layer of `kind`, created (enabled, at its kind's defaults
   and place in the pipeline) when it has none. Returns [db layer-id]."
  [db doc-id kind]
  (if-let [l (layer-of db doc-id kind)]
    [db (:id l)]
    (let [id (rc/next-id db :layer)]
      [(rc/insert db :layer (merge (get-in kinds [kind :params])
                                   {:doc-id doc-id :kind kind :position (get-in kinds [kind :order])
                                    :opacity (default-opacity kind)}
                                   (when-let [f (get-in kinds [kind :feather])] {:feather f})))
       id])))

(defn update-layer [db id f & args]
  (apply rc/update db :layer [= :id id] f args))

(defn set-param [db id k v] (update-layer db id assoc k v))

(defn reset-params
  "Put a layer's settings back to its kind's defaults."
  [db id]
  (update-layer db id #(merge % (dissoc (get-in kinds [(:kind %) :params]) :model))))

(defn denoised-below?
  "Is there an enabled denoise layer under layer `id`?"
  [db id]
  (let [l (layer db id)]
    (boolean (some #(and (= :denoise (:kind %)) (:enabled %) (< (:position %) (:position l)))
                   (layers db (:doc-id l))))))

(defn bump-mask
  "Record that a layer's mask pixels changed."
  [db id]
  (update-layer db id update :mask-rev inc))

;; ---- metadata ----------------------------------------------------------------
(def exif-keys [:make :model :lens :taken :exposure :fnumber :focal :iso :focal35 :orientation])

(defn exif
  "Doc `doc-id`'s shooting metadata as a map of the known fields, or nil."
  [db doc-id]
  (some->> (rc/row db :exif doc-id) (filter (fn [[k v]] (and v (not= k :doc-id)))) (into {}) not-empty))

(defn set-exif
  "Record (or replace) doc `doc-id`'s shooting metadata."
  [db doc-id m]
  (-> (rc/delete db :exif [= :doc-id doc-id])
      (rc/insert :exif (assoc (select-keys m exif-keys) :doc-id doc-id))))

(defn- shutter [s]
  (if (< s 1.0)
    (str "1/" (Math/round (/ 1.0 s)) " s")
    (str (if (== s (Math/floor s)) (long s) s) " s")))

(defn shot-info
  "\"ISO 5000  1/2000 s  f/8  480 mm\" from what the metadata has."
  [{:keys [iso exposure fnumber focal]}]
  (not-empty
   (str/join "  " (cond-> []
                    iso (conj (str "ISO " iso))
                    exposure (conj (shutter exposure))
                    fnumber (conj (str "f/" (if (== fnumber (Math/floor fnumber)) (long fnumber) fnumber)))
                    focal (conj (str (Math/round (double focal)) " mm"))))))

;; ---- staleness ---------------------------------------------------------------
(defn- stack-signatures
  "[each layer's signature, the signature of the composite on top, each
   layer's input signature (its signature but for its own mask)]."
  [db doc-id]
  (loop [ls (layers db doc-id) below [doc-id] acc [] inputs []]
    (if-let [l (first ls)]
      (let [input [(:kind l) (params l) below]
            sig (cond-> input
                  (mask-input? l) (conj [:mask (:id l) (:mask-rev l)]))]
        (recur (rest ls)
               ;; the layer's id stands for its mask: two layers' masks can
               ;; differ at equal revisions, so equal settings aren't equal input
               (if (:enabled l)
                 (conj below [(:id l) sig (:opacity l) (:mask-rev l) (:feather l)])
                 below)
               (conj acc (pr-str sig))
               (conj inputs (pr-str input))))
      [acc below inputs])))

(defn input-signatures
  "Each layer's signature but for its own mask: what it reads and how. A
   repair whose input signature is unchanged since it last finished only
   needs redoing where its mask changed."
  [db doc-id]
  (nth (stack-signatures db doc-id) 2))

(defn signatures
  "Each layer's signature (as stored in :done-sig): its kind and params and
   the signature of the composite it reads."
  [db doc-id]
  (first (stack-signatures db doc-id)))

(defn output-signature
  "The signature of what the doc's whole stack draws, cropped and turned."
  [db doc-id]
  (pr-str [(second (stack-signatures db doc-id))
           (select-keys (doc db doc-id) [:orient :crop-x :crop-y :crop-w :crop-h])]))

(defn- stale-layer?
  "Its signature changed, or it was processed only where its mask was set
   (:partial-rev, the mask revision then) and the mask changed since. Live
   layers never are."
  [l sig]
  (and (:enabled l)
       (not (live? l))
       (or (not= sig (:done-sig l))
           (and (:partial-rev l) (not= (:partial-rev l) (:mask-rev l))))))

(defn stale-indices
  "Indices of the doc's enabled layers whose output no longer matches."
  [db doc-id]
  (vec (keep-indexed (fn [i [l sig]] (when (stale-layer? l sig) i))
                     (map vector (layers db doc-id) (signatures db doc-id)))))

(defn next-stale [db doc-id] (first (stale-indices db doc-id)))

(defn processed?
  "Every enabled layer of the doc is up to date."
  [db doc-id]
  (empty? (stale-indices db doc-id)))

(defn estimate
  "The doc's image estimates {:noise :sharpness}, once measured."
  [doc]
  (when (:noise doc) (select-keys doc [:noise :sharpness])))
