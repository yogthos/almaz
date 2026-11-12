(ns almaz.history
  "Undo and redo. Pure.

   An entry is {:state snapshot, :masks {layer-id mask-copy}, :key, :at}:
   what a document's edits were before a change (`snapshot`), plus copies of
   the masks the change painted (native; the caller frees what `record`,
   `undo` and `redo` hand back as dropped). Snapshots hold only what the
   user edits: settings, switches, strength, feather, orientation and crop,
   never native handles or processing state, so undoing never brings back a
   freed image; a processed layer whose input changes back simply re-runs.

   A history is {:undo [entries, oldest first] :redo [entries]}, at most
   `limit` deep. Edits with the same non-nil :key within `merge-ms` of the
   last entry are one step (a slider drag is many edits)."
  (:require [almaz.doc :as d]))

(def empty {:undo [] :redo []})
(def merge-ms "Same-key edits closer than this are one step." 1500)

(def doc-keys [:orient :crop-x :crop-y :crop-w :crop-h :constrain :ratio-w :ratio-h])

(def layer-keys
  "What the user edits on a layer: every kind's settings, the switch,
   strength and feather."
  (into [:enabled :opacity :feather :mask-sel]
        (distinct (mapcat (comp keys :params val) d/kinds))))

(defn snapshot
  "Doc `doc-id`'s editable state."
  [db doc-id]
  {:doc (select-keys (d/doc db doc-id) doc-keys)
   :layers (into {} (map (juxt :id #(select-keys % layer-keys))) (d/layers db doc-id))})

(defn restore
  "Put doc `doc-id`'s editable state back to `snap`. Layers made since are
   switched off (their native pixels stay owned by their rows)."
  [db doc-id {:keys [doc layers]}]
  (reduce (fn [db l]
            (d/update-layer db (:id l) merge (get layers (:id l) {:enabled false})))
          (d/update-doc db doc-id merge doc)
          (d/layers db doc-id)))

(defn can-undo? [h] (boolean (seq (:undo h))))
(defn can-redo? [h] (boolean (seq (:redo h))))

(defn record
  "Push `entry` (the state before an edit) for an edit with {:key :at}.
   Returns [history dropped-entries]: the entry itself when it merges into
   the last step, the redo entries, and those past `limit`."
  [h entry {:keys [key at]} limit]
  (let [last-e (peek (:undo h))]
    (if (and key last-e (= key (:key last-e)) (< (- at (:at last-e)) merge-ms))
      [h [entry]]
      (let [undo (conj (:undo h) (assoc entry :key key :at at))
            over (max 0 (- (count undo) limit))]
        [{:undo (vec (drop over undo)) :redo []}
         (into (vec (:redo h)) (take over undo))]))))

(defn undo
  "[history entry-to-apply], keeping `current` (the state now) for redo; nil
   with nothing to undo."
  [h current]
  (when-let [e (peek (:undo h))]
    [{:undo (pop (:undo h)) :redo (conj (:redo h) current)} e]))

(defn redo
  "[history entry-to-apply], keeping `current` for undo; nil with nothing
   to redo."
  [h current]
  (when-let [e (peek (:redo h))]
    [{:undo (conj (:undo h) current) :redo (pop (:redo h))} e]))
