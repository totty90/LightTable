(ns lt.objs.editor.pool
  (:require [lt.object :as object]
            [lt.objs.app :as app]
            [lt.objs.files :as files]
            [lt.objs.file-manager :as fileman]
            [lt.objs.canvas :as canvas]
            [lt.objs.settings :as settings]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.popup :as popup]
            [lt.objs.notifos :as notifos]
            [lt.objs.editor :as editor]
            [lt.util.cljs :refer [->dottedkw]]
            [clojure.string :as string]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [defui]]))

(defn get-all []
  (object/by-tag :editor))

(object/behavior* ::theme-changed
                  :triggers #{:theme-change}
                  :reaction (fn [this theme]
                              (doseq [ed (get-all)
                                      :let [e (:ed @ed)]]
                                (editor/set-options e {:theme theme}))))

(object/behavior* ::line-numbers-changed
                  :triggers #{:line-numbers-change}
                  :reaction (fn [this numbers?]
                              (doseq [ed (get-all)
                                      :let [e (:ed @ed)]]
                                (editor/set-options e {:lineNumbers numbers?}))))

(object/behavior* ::options-changed
                  :triggers #{:options-changed}
                  :reaction (fn [this opts]
                              (doseq [ed (get-all)
                                      :let [e (:ed @ed)]]
                                (editor/set-options e opts))))

(object/object* ::pool
                :tags #{:editor.pool})

(object/tag-behaviors :editor.pool [::theme-changed ::line-numbers-changed ::options-changed])

(defn unsaved? []
  (some #(:dirty (deref %)) (object/by-tag :editor)))

(defn by-path [path]
  (when path
    (let [path (string/lower-case path)]
      (filter #(= (-> @% :info (:path "") string/lower-case) path) (object/by-tag :editor)))))

(defn containing-path [path]
  (let [path (string/lower-case path)]
    (filter #(> (.indexOf (-> @% :info :path string/lower-case) path) -1) (object/by-tag :editor))))

(defui button [label & [cb]]
       [:div.button.right label]
       :click (fn []
                (when cb
                  (cb))))

(defn unsaved-prompt [on-yes]
  (popup/popup! {:header "You will lose changes."
                 :body "If you close now, you'll lose any unsaved changes. Are you sure you want to do that?"
                 :buttons [{:label "Discard changes"
                            :action on-yes}
                           popup/cancel-button]}))

(def pool (object/create ::pool))

(object/behavior* ::track-active
                  :triggers #{:active}
                  :reaction (fn [this]
                              (object/merge! pool {:last this})))

(object/behavior* ::stop-close-dirty
                  :triggers #{:close}
                  :reaction (fn [this]
                              (when (unsaved?)
                                (app/prevent-close)
                                (unsaved-prompt (partial app/close true)))))

(object/behavior* ::stop-reload-dirty
                  :triggers #{:reload}
                  :reaction (fn [this]
                              (when (unsaved?)
                                (app/prevent-close)
                                (unsaved-prompt app/refresh))))

(object/behavior* ::ed-close
                  :triggers #{:close}
                  :reaction (fn [this]
                              (if (:dirty @this)
                                (unsaved-prompt #(object/raise this :close.force))
                                (object/raise this :close.force))))

(object/behavior* ::focus-last-on-focus
                  :triggers #{:focus!}
                  :reaction (fn [this]
                              (focus-last)))

(defn make-transient-dirty [ed]
  (object/merge! ed {:dirty true})
  (object/update! ed [:info] assoc :path nil)
  (object/remove-tags ed [:editor.file-backed])
  (object/add-tags ed [:editor.transient]))

(defn active-warn [ed popup]
  (if-not (= (last-active) ed)
    (object/merge! ed {:active-warn popup})
    (popup/popup! popup)))

(defn reload [ed]
  (editor/set-val ed (:content (files/open-sync (-> @ed :info :path))))
  (fileman/update-stats (-> @ed :info :path))
  (object/merge! ed {:dirty false}))

(object/behavior* ::warn-on-active
                  :triggers #{:active}
                  :reaction (fn [this]
                              (when (:active-warn @this)
                                (popup/popup! (:active-warn @this))
                                (object/merge! this {:active-warn nil}))))

(object/behavior* ::watched.update
                  :triggers #{:watched.update}
                  :reaction (fn [ws f stat]
                              (when (files/file? f)
                                (when-let [ed (first (by-path f))]
                                  (when-not (fileman/check-mtime (fileman/->stats f) stat)
                                    (if (:dirty @ed)
                                      (active-warn ed {:header "This file has been modified."
                                                       :body "This file seems to have been modified outside of Light Table. Do you want to load the latest and lose your changs?"
                                                       :buttons [{:label "Reload from disk"
                                                                  :action (fn []
                                                                            (reload ed))}
                                                                 {:label "Cancel"}
                                                                 ]})
                                      (reload ed)))))))

(defn warn-delete [ed]
  (active-warn ed {:header "This file has been deleted."
                   :body "This file seems to have been deleted and we've marked it as unsaved."
                   :buttons [{:label "Save as.."
                              :action (fn []
                                        (object/raise ed :save))}
                             {:label "ok"}]}))

(object/behavior* ::watched.delete
                  :triggers #{:watched.delete}
                  :reaction (fn [ws del]
                              (if (files/file? del)
                                (when-let [ed (first (by-path del))]
                                  (warn-delete ed)
                                  (make-transient-dirty ed)
                                  (when-let [ts (:lt.objs.tabs/tabset @ed)]
                                    (object/raise ts :tab.updated)))
                                (let [open (filter #(if-let  [path (-> @% :info :path)]
                                                      (= 0 (.indexOf path del))
                                                      false)
                                                   (object/by-tag :editor))]
                                  (doseq [ed open]
                                    (warn-delete ed)
                                    (make-transient-dirty ed))))))

(object/behavior* ::watched.rename
                  :triggers #{:watched.rename :rename}
                  :reaction (fn [this old neue]
                              (if (files/file? old)
                                (when-let [ed (first (by-path old))]
                                  (fileman/update-stats neue)
                                  (object/update! ed [:info] assoc :path neue :name (files/basename neue))
                                  (when-let [ts (:lt.objs.tabs/tabset @ed)]
                                    (object/raise ts :tab.updated)))
                                (let [open (filter #(= 0 (.indexOf (-> @% :info :path) old)) (object/by-tag :editor))]
                                  (doseq [ed open
                                          :let [neue-path (string/replace (-> @ed :info :path) old neue)]]
                                    (fileman/update-stats neue-path)
                                    (object/update! ed [:info] assoc :path neue-path :name (files/basename neue-path)))))))

(defn last-active []
  (let [l (:last @pool)]
    (when (and l @l)
      l)))

(defn focus-last []
  (when-let [ed (last-active)]
    (when-let [ed (:ed @ed)]
      (dom/focus js/document.body)
      (editor/focus ed))))

(defn create [info]
  (let [ed (object/create :lt.objs.editor/editor info)]
    (object/add-tags ed (:tags info []))
    (object/raise pool :create ed info)
    (object/raise ed :create)
    ed))

(cmd/command {:command :focus-last-editor
              :desc "Editor: Focus last active editor"
              :hidden true
              :exec (fn []
                      (focus-last))})


(def syntax-selector (cmd/filter-list {:items (fn []
                                                (sort-by :name (-> @files/files-obj :types vals)))
                                       :key :name
                                       :placeholder "Syntax"}))

(defn set-syntax [ed new-syn]
  (let [prev-info (-> @ed :info)]
    (when prev-info
      (println "removing tags: " (:tags prev-info))
      (object/remove-tags ed (:tags prev-info)))
    (object/update! ed [:info] merge (dissoc new-syn :name))
    (editor/set-mode ed (:mime new-syn))
    (object/add-tags ed (:tags new-syn))))

(object/behavior* ::set-syntax
                  :triggers #{:select}
                  :reaction (fn [this v]
                              (cmd/exec-active! v)))

(object/add-behavior! syntax-selector ::set-syntax)

(cmd/command {:command :set-syntax
              :desc "Editor: Set current editor syntax"
              :options syntax-selector
              :exec (fn [syn]
                      (println syn)
                      (if-let [last (last-active)]
                        (set-syntax last syn)
                        (notifos/set-msg! "Set syntax requires an active editor")))})

;;****************************************************
;; Editor commands
;;****************************************************

(cmd/command {:command :comment-selection
              :desc "Editor: Comment line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [cursor (editor/->cursor cur "start")]
                          (if (editor/selection? cur)
                            (editor/line-comment cur cursor (editor/->cursor cur "end"))
                            (editor/line-comment cur cursor cursor)))))})

(cmd/command {:command :uncomment-selection
              :desc "Editor: Uncomment line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [cursor (editor/->cursor cur "start")]
                          (if (editor/selection? cur)
                            (editor/uncomment cur cursor (editor/->cursor cur "end"))
                            (editor/uncomment cur cursor cursor)))))})

(cmd/command {:command :toggle-comment-selection
              :desc "Editor: Toggle comment line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [cursor (editor/->cursor cur "start")
                              [start end] (if (editor/selection? cur)
                                            [cursor (editor/->cursor cur "end")]
                                            [cursor cursor])]
                          (when-not (editor/uncomment cur start end)
                            (editor/line-comment cur cursor (editor/->cursor cur "end"))))))})

(cmd/command {:command :indent-selection
              :desc "Editor: Indent line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [line (-> cur (editor/->cursor "start") :line)]
                          (if (editor/selection? cur)
                            (editor/indent-selection cur "add")
                            (editor/indent-line cur line "add")))))})

(cmd/command {:command :unindent-selection
              :desc "Editor: Unindent line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [line (-> cur (editor/->cursor "start") :line)]
                          (if (editor/selection? cur)
                            (editor/indent-selection cur "subtract")
                            (editor/indent-line cur line "subtract")))))})

(cmd/command {:command :smart-indent-selection
              :desc "Editor: Smart indent line(s)"
              :exec (fn []
                      (when-let [cur (last-active)]
                        (let [line (-> cur (editor/->cursor "start") :line)]
                          (if (editor/selection? cur)
                            (editor/indent-selection cur "smart")
                            (editor/indent-line cur line "smart")))))})

(cmd/command {:command :line-numbers
              :desc "Editor: Toggle line numbers"
              :exec (fn []
                      (let [v (not (settings/fetch :line-numbers))]
                        (settings/store! :line-numbers v)
                        (object/raise pool :line-numbers-change v))
                      )})

(cmd/command {:command :toggle-wrap
              :desc "Editor: Toggle line wrapping for current"
              :exec (fn []
                      (let [ed (last-active)
                            v (not (editor/option ed :lineWrapping))]
                        (editor/set-options ed {:lineWrapping v}))
                      )})

(cmd/command {:command :editor.select-all
              :desc "Editor: Select all"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.selectAll (editor/->cm-ed ed))))})

(cmd/command {:command :editor.kill-line
              :desc "Editor: Kill line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.killLine (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-line
              :desc "Editor: Delete line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.deleteLine (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-line-left
              :desc "Editor: Delete line left"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delLineLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.undo
              :desc "Editor: Undo"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.undo (editor/->cm-ed ed))))})

(cmd/command {:command :editor.redo
              :desc "Editor: Redo"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.redo (editor/->cm-ed ed))))})

(cmd/command {:command :editor.doc-start
              :desc "Editor: Move to first line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goDocStart (editor/->cm-ed ed))))})

(cmd/command {:command :editor.doc-end
              :desc "Editor: Move to last line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goDocEnd (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-start
              :desc "Editor: Move to start of the line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineStart (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-start-smart
              :desc "Editor: Move to first non-whitespace char in the line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.gotoLineStartSmart (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-end
              :desc "Editor: Move to end of the line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineEnd (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-right
              :desc "Editor: Goto the right of the line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineRight (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-left
              :desc "Editor: Goto the left of the line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-up
              :desc "Editor: Previous line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineUp (editor/->cm-ed ed))))})

(cmd/command {:command :editor.line-down
              :desc "Editor: Next line"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goLineDown (editor/->cm-ed ed))))})

(cmd/command {:command :editor.page-up
              :desc "Editor: Page up"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goPageUp (editor/->cm-ed ed))))})

(cmd/command {:command :editor.page-down
              :desc "Editor: Page down"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goPageDown (editor/->cm-ed ed))))})

(cmd/command {:command :editor.char-left
              :desc "Editor: Move left one character"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goCharLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.char-right
              :desc "Editor: Move right one character"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goCharRight (editor/->cm-ed ed))))})


(cmd/command {:command :editor.column-left
              :desc "Editor: Move left one column"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goColumnLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.column-right
              :desc "Editor: Move right one column"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goColumnRight (editor/->cm-ed ed))))})

(cmd/command {:command :editor.word-left
              :desc "Editor: Move left one word"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goWordLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.word-right
              :desc "Editor: Move right one word"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goWordRight (editor/->cm-ed ed))))})

(cmd/command {:command :editor.group-left
              :desc "Editor: Move left one group"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goGroupLeft (editor/->cm-ed ed))))})

(cmd/command {:command :editor.group-right
              :desc "Editor: Move right one group"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.goGroupRight (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-char-left
              :desc "Editor: Delete character to the left"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delCharBefore (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-char-right
              :desc "Editor: Delete character to the right"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delCharAfter (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-word-left
              :desc "Editor: Delete word to the left"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delWordBefore (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-word-right
              :desc "Editor: Delete word to the right"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delWordAfter (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-group-left
              :desc "Editor: Delete group to the left"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delGroupBefore (editor/->cm-ed ed))))})

(cmd/command {:command :editor.delete-group-right
              :desc "Editor: Delete group to the right"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.delGroupAfter (editor/->cm-ed ed))))})

(cmd/command {:command :editor.transpose-chars
              :desc "Editor: Transpose characters"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.transposeChars (editor/->cm-ed ed))))})

(cmd/command {:command :editor.new-line-indent
              :desc "Editor: Newline and indent"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.newlineAndIndent (editor/->cm-ed ed))))})

(cmd/command {:command :editor.toggle-overwrite
              :desc "Editor: Toggle overwrite"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (js/CodeMirror.commands.toggleOverwrite (editor/->cm-ed ed))))})

(cmd/command {:command :editor.cut
              :desc "Editor: Cut"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/cut ed)))})

(cmd/command {:command :editor.copy
              :desc "Editor: Copy"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/copy ed)))})

(cmd/command {:command :editor.paste
              :desc "Editor: Paste"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/paste ed)))})

(cmd/command {:command :editor.select-all
              :desc "Editor: Select all"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/select-all ed)))})

(cmd/command {:command :editor.undo
              :desc "Editor: Undo"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/undo ed)))})

(cmd/command {:command :editor.redo
              :desc "Editor: Redo"
              :hidden true
              :exec (fn []
                      (when-let [ed (last-active)]
                        (editor/redo ed)))})
