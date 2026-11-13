(ns almaz.keys-test
  "The app's keyboard shortcuts: the kit's combos and how they are shown."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.keys :as k]))

(deftest labels
  (testing "macOS: symbols in Apple's order (Ctrl Option Shift Command), no separators"
    (is (= "⌘O" (k/label :mac "ctrl+o")))
    (is (= "⇧⌘Z" (k/label :mac "shift+ctrl+z")))
    (is (= "⌘0" (k/label :mac "ctrl+0")))
    (is (= "⌘-" (k/label :mac "ctrl+minus")))
    (is (= "⌘=" (k/label :mac "ctrl+equal"))))
  (testing "Linux: words joined with +, Ctrl first"
    (is (= "Ctrl+O" (k/label :linux "ctrl+o")))
    (is (= "Ctrl+Shift+Z" (k/label :linux "shift+ctrl+z")))
    (is (= "Ctrl+-" (k/label :linux "ctrl+minus")))))

(deftest bindings
  (let [mac (k/bindings :mac) linux (k/bindings :linux)]
    (testing "the shared ones (Command on macOS is the kit's Ctrl)"
      (doseq [b [mac linux]]
        (is (= :open (b "ctrl+o")))
        (is (= :export (b "ctrl+e")))
        (is (= :undo (b "ctrl+z")))
        (is (= :redo (b "shift+ctrl+z")))
        (is (= :zoom-fit (b "ctrl+0")))
        (is (= :zoom-100 (b "ctrl+1")))
        (is (= :zoom-in (b "ctrl+equal")))
        (is (= :zoom-out (b "ctrl+minus")))))
    (testing "Linux: Ctrl+Y redoes and Ctrl+Q quits (macOS's app menu has Quit)"
      (is (= :redo (linux "ctrl+y")))
      (is (= :quit (linux "ctrl+q")))
      (is (nil? (mac "ctrl+q"))))
    (testing "every action has a label to show"
      (is (= "⌘E" (k/shortcut :mac :export)))
      (is (= "Ctrl+Shift+Z" (k/shortcut :linux :redo))))))
