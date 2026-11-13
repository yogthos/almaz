(ns almaz.ui.shortcuts
  "The app's keyboard shortcuts (almaz.keys), bound with glimmer-bui's
   bind-key!: they work wherever the pointer is, and not while a text field
   has the keys."
  (:require [glimmer-bui.core :as gb]
            [glimmer-bui.ffi :as bk]
            [almaz.actions :as act]
            [almaz.keys :as k]
            [almaz.state :as s]))

(def os (k/os))

(defn label
  "`action`'s shortcut as this platform shows it, e.g. \"⌘E\" or \"Ctrl+E\"."
  [action]
  (k/shortcut os action))

(defn- run-action! [action]
  (case action
    :open (act/open-dialog!)
    :export (if @s/export-open* (act/export!) (act/open-export!))
    :undo (act/undo!)
    :redo (act/redo!)
    :zoom-fit (act/zoom-fit!)
    :zoom-100 (act/zoom-to! 1.0)
    :zoom-in (act/zoom-by! 1.5)
    :zoom-out (act/zoom-by! (/ 1 1.5))
    :escape (act/close-export!)
    :quit (bk/window-quit)
    nil))

(defn install!
  "Bind every shortcut (before or after the window is up)."
  []
  (doseq [[combo action] (k/bindings os)]
    (gb/bind-key! combo #(run-action! action))))

(defonce ^:private installed (do (install!) true))
