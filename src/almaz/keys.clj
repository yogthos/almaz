(ns almaz.keys
  "The app's keyboard shortcuts, as the BUI kit's combos (\"shift+ctrl+z\"),
   and how to show them. The kit reports macOS's Command as Ctrl, so one
   combo is Cmd+Z on a Mac and Ctrl+Z on Linux. Pure."
  (:require [clojure.string :as str]))

(defn os
  "The platform's shortcut convention: :mac or :linux."
  []
  (if (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac") :mac :linux))

(def ^:private common
  [["ctrl+o" :open]
   ["ctrl+e" :export]
   ["ctrl+z" :undo]
   ["shift+ctrl+z" :redo]
   ["ctrl+0" :zoom-fit]
   ["ctrl+1" :zoom-100]
   ["ctrl+equal" :zoom-in]
   ["ctrl+=" :zoom-in]          ; macOS names the key by its character
   ["ctrl+minus" :zoom-out]
   ["escape" :escape]])           ; closes the export panel

(def ^:private per-os
  {:mac   []                      ; Quit (Cmd+Q) is in the app menu
   :linux [["ctrl+y" :redo] ["ctrl+q" :quit]]})

(defn bindings
  "combo -> action for platform `os`."
  [os]
  (into {} (concat common (per-os os))))

(def ^:private key-names {"minus" "-" "equal" "=" "plus" "+" "space" "Space" "escape" "Esc"})

(defn label
  "How `combo` reads on platform `os`: \"⇧⌘Z\" on a Mac, \"Ctrl+Shift+Z\" on Linux."
  [os combo]
  (let [parts (str/split combo #"\+(?=.)")
        mods (set (butlast parts))
        k (last parts)
        k (get key-names k (str/upper-case k))]
    (if (= os :mac)
      (str (when (mods "alt") "⌥") (when (mods "shift") "⇧") (when (mods "ctrl") "⌘") k)
      (str/join "+" (concat (keep (fn [[m s]] (when (mods m) s)) [["ctrl" "Ctrl"] ["alt" "Alt"] ["shift" "Shift"]])
                            [k])))))

(defn shortcut
  "The label of `action`'s first combo on platform `os` (nil when unbound)."
  [os action]
  (some (fn [[c a]] (when (= a action) (label os c))) (concat common (per-os os))))
