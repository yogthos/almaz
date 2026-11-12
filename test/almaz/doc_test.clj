(ns almaz.doc-test
  "The document model over relatum: documents, their filter layers, the
   layers' order, and which ones need (re)processing after an edit."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.color :as c]
            [almaz.doc :as d]))

(def db0 (d/add-doc (d/empty-db) {:path "/x/DSC_1.NEF" :raw? true}))

(defn- kinds [db] (mapv :kind (d/layers db 1)))
(defn- ids [db] (mapv :id (d/layers db 1)))

(deftest documents
  (let [db (d/add-doc db0 {:path "/x/b.jpg" :raw? false})]
    (testing "rows carry their name, status and filmstrip order"
      (is (= "DSC_1.NEF" (:name (d/doc db 1))))
      (is (= :pending (:status (d/doc db 1))))
      (is (true? (:raw (d/doc db 1))))
      (is (= [1 2] (mapv :id (d/docs db)))))
    (testing "the schema rejects bad writes"
      (is (thrown? Exception (d/add-doc db {:path "/x/b.jpg" :raw? false})) "duplicate path")
      (is (thrown? Exception (d/update-doc db 1 assoc :status :bogus)) "unknown status"))
    (testing "removing a document removes its layers"
      (let [[db' _] (d/ensure-layer db 1 :denoise)
            db' (d/remove-doc db' 1)]
        (is (nil? (d/doc db' 1)))
        (is (= [] (d/layers db' 1)))
        (is (= [2] (mapv :id (d/docs db'))))))))

(deftest ensure-layer
  (testing "layers keep pipeline order: denoise below sharpen, colour on top"
    (let [[db _] (d/ensure-layer db0 1 :vignette)
          [db _] (d/ensure-layer db 1 :sharpen)
          [db _] (d/ensure-layer db 1 :levels)
          [db _] (d/ensure-layer db 1 :denoise)]
      (is (= [:denoise :sharpen :levels :vignette] (kinds db)))
      (is (apply distinct? (ids db)))))
  (testing "one layer per kind: asking again answers the same layer"
    (let [[db a] (d/ensure-layer db0 1 :denoise)
          [db2 b] (d/ensure-layer db 1 :denoise)]
      (is (= a b))
      (is (= 1 (count (d/layers db2 1))))
      (is (= a (:id (d/layer-of db2 1 :denoise))))))
  (testing "the schema refuses a second layer of a kind"
    (let [[db _] (d/ensure-layer db0 1 :denoise)
          [db b] (d/ensure-layer db 1 :sharpen)]
      (is (thrown? Exception (d/update-layer db b assoc :kind :denoise)))))
  (testing "a new layer is enabled, unmasked, needs processing, has its kind's defaults"
    (let [[db id] (d/ensure-layer db0 1 :denoise)
          l (d/layer db id)]
      (is (:enabled l))
      (is (= 0.5 (:opacity l)) "the AI filters start at half strength")
      (is (= 1.0 (:opacity (d/layer (first (d/ensure-layer db0 1 :repair)) 1))) "the rest at full")
      (is (= 1.0 (d/default-opacity :hsl)))
      (is (nil? (:done-sig l)))
      (is (= (get-in d/kinds [:denoise :params]) (d/params l)))))
  (testing "a layer must belong to an existing document"
    (is (thrown? Exception (d/ensure-layer db0 99 :denoise))))
  (testing "opacity stays within [0, 1]"
    (let [[db id] (d/ensure-layer db0 1 :denoise)]
      (is (thrown? Exception (d/update-layer db id assoc :opacity 1.5))))))

(deftest tabs
  (is (= [:denoise :sharpen :repair :focus] (d/kinds-in :enhance)))
  (is (= [:light :levels :curves :balance :hsl :selective :bw :vignette] (d/kinds-in :color))))

(deftest colour-layers
  (let [[db id] (d/ensure-layer db0 1 :balance)]
    (testing "settings start neutral and stay in range"
      (is (= [:balance (c/defaults :balance)] (d/colour (d/layer db id))))
      (is (thrown? Exception (d/set-param db id :bal-shadows-cr 1.5))))
    (testing "reset puts the defaults back"
      (let [db (d/set-param db id :bal-shadows-cr 0.5)]
        (is (= 0.0 (:bal-shadows-cr (d/layer (d/reset-params db id) id))))))
    (testing "a colour layer is live: never queued"
      (is (d/processed? (d/set-param db id :bal-midtones-sat 0.4) 1)))))

(defn- processed
  "Mark every layer of doc 1 as processed against its current signature."
  [db]
  (reduce (fn [acc [l sig]] (d/update-layer acc (:id l) assoc :done-sig sig))
          db (map vector (d/layers db 1) (d/signatures db 1))))

(deftest staleness
  (let [[db a] (d/ensure-layer db0 1 :denoise)
        [db b] (d/ensure-layer db 1 :sharpen)
        db (processed db)]
    (testing "fresh after processing"
      (is (nil? (d/next-stale db 1)))
      (is (d/processed? db 1)))
    (testing "a param change re-runs that layer and everything above it"
      (let [y (d/set-param db a :detail 0.3)]
        (is (= 0 (d/next-stale y 1)))
        (is (= [0 1] (d/stale-indices y 1)))))
    (testing "opacity or a mask edit leaves the layer itself valid but re-runs the ones above"
      (is (= [1] (d/stale-indices (d/update-layer db a assoc :opacity 0.3) 1)))
      (is (= [1] (d/stale-indices (d/bump-mask db a) 1))))
    (testing "the top layer's opacity invalidates nothing"
      (is (= [] (d/stale-indices (d/update-layer db b assoc :opacity 0.2) 1))))
    (testing "disabled layers are skipped and don't count as input"
      (let [y (d/update-layer db a assoc :enabled false)]
        (is (= [1] (d/stale-indices y 1)))
        (is (= [1] (d/stale-indices (d/set-param y a :detail 0.9) 1)))))
    (testing "a layer switched back on keeps its output but re-runs the ones above"
      (let [db2 (processed (d/update-layer db a assoc :enabled false))]
        (is (= [1] (d/stale-indices (d/update-layer db2 a assoc :enabled true) 1)))))))

(deftest partial-layers
  (testing "a layer processed only where its mask was set goes stale when the mask changes"
    (let [[db id] (d/ensure-layer db0 1 :denoise)
          db (processed db)
          partial (d/update-layer db id assoc :partial-rev 0)]
      (is (= [] (d/stale-indices partial 1)) "same mask: still valid")
      (is (= [0] (d/stale-indices (d/bump-mask partial id) 1)) "mask edited: re-run the skipped tiles")
      (is (= [] (d/stale-indices (d/bump-mask db id) 1)) "a fully processed layer stays valid"))))

(deftest layer-lookup
  (let [[db id] (d/ensure-layer db0 1 :denoise)]
    (is (= :denoise (:kind (d/layer db id))))
    (is (= 0 (d/layer-index db id)))
    (is (nil? (d/layer db 999)))))

(deftest denoised-below
  (let [[db s] (d/ensure-layer db0 1 :sharpen)
        [db2 n] (d/ensure-layer db 1 :denoise)]
    (is (not (d/denoised-below? db s)) "nothing under the sharpen layer")
    (is (d/denoised-below? db2 s))
    (is (not (d/denoised-below? (d/update-layer db2 n assoc :enabled false) s)) "a disabled denoise doesn't count")))

(deftest exif-rows
  (let [db (d/set-exif db0 1 {:iso 5000 :fnumber 8.0 :exposure 0.0005})]
    (is (= {:iso 5000 :fnumber 8.0 :exposure 0.0005} (select-keys (d/exif db 1) [:iso :fnumber :exposure])))
    (testing "setting again replaces"
      (is (= 100 (:iso (d/exif (d/set-exif db 1 {:iso 100}) 1)))))
    (testing "goes with its document"
      (is (nil? (d/exif (d/remove-doc db 1) 1))))
    (testing "shot summary"
      (is (= "ISO 5000  1/2000 s  f/8" (d/shot-info (d/exif db 1)))))))

(deftest positions-are-unique-per-document
  (testing "the schema refuses two layers at one position"
    (let [[db _] (d/ensure-layer db0 1 :denoise)
          [db b] (d/ensure-layer db 1 :sharpen)]
      (is (thrown? Exception (d/update-layer db b assoc :position 0))))))

(deftest light-layers
  (let [[db s] (d/ensure-layer db0 1 :sharpen)
        [db l] (d/ensure-layer db 1 :light)
        [db n] (d/ensure-layer db 1 :denoise)]
    (testing "light adjustments go on top of the filters, at neutral settings"
      (is (= [:denoise :sharpen :light] (kinds db)))
      (is (= {:exposure 0.0 :contrast 0.0 :highlights 0.0 :shadows 0.0 :whites 0.0 :blacks 0.0 :saturation 0.0}
             (d/tone (d/layer db l))))
      (is (= 4 (:position (d/layer db l))) "the filters stay below it"))
    (testing "an adjustment is computed as it's drawn: never queued for processing"
      (let [db (processed db)]
        (is (d/processed? db 1))
        (let [y (d/set-param db l :exposure 1.5)]
          (is (d/processed? y 1))
          (is (= 1.5 (:exposure (d/tone (d/layer y l))))))
        (is (= [0 1] (d/stale-indices (d/set-param (d/update-layer db l assoc :done-sig nil) n :detail 0.5) 1))
            "editing a filter below doesn't queue it either")))
    (testing "the settings stay in range"
      (is (thrown? Exception (d/set-param db l :exposure 9.0)))
      (is (thrown? Exception (d/set-param db l :contrast -1.5))))
    (is (= :light (:kind (d/layer db l))))
    (is s)))

(deftest output-signature
  (let [[db l] (d/ensure-layer db0 1 :light)
        sig (d/output-signature db 1)]
    (testing "names what the whole stack draws: any change to it is a new picture"
      (is (= sig (d/output-signature db 1)))
      (is (not= sig (d/output-signature (d/set-param db l :exposure 1.0) 1)))
      (is (not= sig (d/output-signature (d/update-layer db l assoc :opacity 0.5) 1)))
      (is (not= sig (d/output-signature (d/bump-mask db l) 1))))
    (testing "a disabled layer draws nothing"
      (is (= (d/output-signature db0 1)
             (d/output-signature (d/update-layer db l assoc :enabled false) 1))))
    (testing "turning or cropping is a new picture"
      (is (not= sig (d/output-signature (d/update-doc db 1 assoc :orient 1) 1)))
      (is (not= sig (d/output-signature (d/update-doc db 1 assoc :crop-x 0 :crop-y 0 :crop-w 10 :crop-h 10) 1))))))

(deftest geometry-columns
  (testing "orientation stays 0..7"
    (is (thrown? Exception (d/update-doc db0 1 assoc :orient 8))))
  (testing "a doc starts upright, uncropped, the crop keeping the photo's shape"
    (is (= {:orient 0 :crop-w nil :constrain :original}
           (select-keys (d/doc db0 1) [:orient :crop-w :constrain])))))

(deftest repair-layers
  (let [[db n] (d/ensure-layer db0 1 :denoise)
        [db r] (d/ensure-layer db 1 :repair)
        [db l] (d/ensure-layer db 1 :light)
        db (processed db)]
    (testing "repairs go after the filters and before the colour adjustments"
      (is (= [:denoise :repair :light] (kinds db))))
    (testing "its mask starts empty: nothing is repaired until painted"
      (is (= 0.0 (d/mask-fill (d/layer db r))))
      (is (= 1.0 (d/mask-fill (d/layer db n)))))
    (testing "painting re-runs the repair itself, not just the layers above"
      (is (= [1] (d/stale-indices (d/bump-mask db r) 1)))
      (is (= [] (d/stale-indices (d/bump-mask db l) 1)) "other layers' masks only change their blend")
      (is (= [1] (d/stale-indices (d/bump-mask db n) 1)) "a mask below changes what it repairs"))
    (testing "its input signature ignores its own mask, not what's below"
      (let [in #(nth (d/input-signatures % 1) 1)]
        (is (= (in db) (in (d/bump-mask db r))))
        (is (not= (in db) (in (d/bump-mask db n))))))))

(deftest lens-layers
  (let [[db s] (d/ensure-layer db0 1 :sharpen)
        [db l] (d/ensure-layer db 1 :lens)]
    (testing "lens correction lives on the Dimensions tab, under every other layer"
      (is (= [:lens] (d/kinds-in :dimensions)))
      (is (= [:lens :sharpen] (kinds db))))
    (testing "on by default: distortion, TCA, vignetting and auto-fill, method chosen automatically"
      (is (= {:model :classic :lens-method :auto :lens-dist true :lens-tca true :lens-vig true
              :lens-fill true :lens-id nil}
             (d/params (d/layer db l)))))
    (testing "it is processed, and editing it makes the layers above stale"
      (let [db (processed db)
            y (d/set-param db l :lens-tca false)]
        (is (not (d/live? (d/layer db l))))
        (is (= [0 1] (d/stale-indices y 1)))))))

(deftest focus-layers
  (testing "Focus is an Enhance filter after the repairs, processed, not live"
    (is (= [:denoise :sharpen :repair :focus] (d/kinds-in :enhance)))
    (let [[db f] (d/ensure-layer db0 1 :focus)]
      (is (not (d/live? (d/layer db f))))
      (is (= {:model :classic :focus-clarity 0.0 :focus-radius 80.0 :focus-blur 0.0}
             (d/params (d/layer db f)))))))
