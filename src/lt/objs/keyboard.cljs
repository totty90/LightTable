(ns lt.objs.keyboard
  (:refer-clojure :exclude [keys])
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.settings :as settings]
            [lt.objs.platform :as platform]
            [lt.objs.metrics :as metrics]
            [lt.objs.context :as ctx]
            [lt.util.js :refer [every wait]]
            [lt.util.events :as utev]))

(def capturing? true)
(def keys (atom {}))
(def key-map (atom {}))
(def chords (js-obj :current nil :chords #{}))
(def chord-timeout 1000)

(defn activity []
  (metrics/used!))

(defn chord-variants [k]
  (let [splits (-> (string/split k " ")
                   (butlast))]
    (reduce (fn [res cur]
              (conj res (str (last res) " " cur)))
            [(first splits)]
            (rest splits))))

(defn extract-chords [ks]
  (reduce (fn [chords [k _]]
            (if-not (> (.indexOf k " ") -1)
              chords
              (apply conj chords (chord-variants k))))
          #{}
          ks))

(defn merge-keys [_ _ _ ctx]
  (let [ctx-set (object/specificity-sort ctx)
        ks @keys
        neue (apply merge {} (map ks ctx-set))]
    (set! chords (js-obj :current nil :chords (extract-chords neue)))
    (reset! key-map neue)))

(defn refresh []
  (merge-keys nil nil nil (ctx/current)))

;;When the context changes, create our new keymap
(add-watch ctx/contexts :commands2 merge-keys)
(refresh)

(defn ->keystr [ev]
  (str
   (when (.-ctrlKey ev) "ctrl-")
   (when (.-metaKey ev) (if (platform/mac?)
                          "cmd-"
                          "meta-"))
   (when (.-altKey ev) "alt-")
   (when (or (.-altGraphKey ev) altgr) "altgr-")
   (when (.-shiftKey ev) "shift-")
   (. (or (.-key ev) "") toLowerCase)))

(defn chord|mapping [ev]
  (let [current (aget chords :current)
        cur-chords (aget chords :chords)
        [ks ch] (if current
                  [(str current " " (->keystr ev)) (str current " " (aget ev "char"))]
                  [(->keystr ev) (aget ev "char")])]
    (if-let [chord (or (cur-chords ch) (cur-chords ks))]
      (do
        (aset chords :current chord)
        (when chord-timeout
          (wait chord-timeout #(aset chords :current nil)))
        [])
      (do
        (aset chords :current nil)
        (or (@key-map ch) (@key-map ks) (when current []))))))

(def ^:dynamic *capture* true)

(defn passthrough []
  (set! *capture* false))

(defn disable []
  (set! capturing? false))

(defn enable []
  (set! capturing? true))

(defn all-mappings [key]
  (reduce (fn [res [ctx keys]]
            (if-not (keys key)
              res
              (conj res [ctx (keys key)])))
          []
          @keys))

(defn trigger [cmd]
  (activity)
  (if (coll? cmd)
    (apply cmd/exec! cmd)
    (cmd/exec! cmd))
  *capture*)

(defn capture [ev]
  (activity)
  (binding [*capture* true]
    (when-let [cs (chord|mapping ev)]
      (doseq [c cs]
        (trigger c))
      *capture*)))

(defn capture-up [ev]
  (or (@key-map (aget ev "char")) (@key-map (->keystr ev))))

(def meta (if (platform/mac?)
            "cmd"
            "ctrl"))

(defn cmd->bindings [cmd]
    (filter #(-> % second seq)
            (for [[ctx ms] @keys]
              [ctx (-> (filter #(= (-> % second first) cmd) ms)
                       first
                       first)])))

(utev/capture :keydown
              (fn [ev]
                (when (and capturing?
                           (capture ev))
                  (.preventDefault ev)
                  (.stopPropagation ev))))

(utev/capture :keyup
              (fn [ev]
                (when (and capturing?
                           (capture-up ev))
                  (.preventDefault ev)
                  (.stopPropagation ev))))

(object/behavior* ::chord-timeout
                  :triggers #{:object.instant}
                  :desc "App: Set the timeout for chorded shortcuts"
                  :type :user
                  :reaction (fn [this timeout]
                              (set! chord-timeout timeout)))
