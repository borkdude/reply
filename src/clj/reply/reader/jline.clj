(ns reply.reader.jline
  (:refer-clojure :exclude [read])
  (:require [reply.reader.jline.completion :as jline.completion]
            [reply.eval-state :as eval-state]
            [clojure.main])
  (:import [java.io File IOException PrintStream ByteArrayOutputStream]
           [reply.reader.jline JlineInputReader]
           [reply.hacks CustomizableBufferLineNumberingPushbackReader]
           [jline.console ConsoleReader]
           [jline.console.history FileHistory]
           [jline.internal Log]))

(def jline-reader (atom nil))
(def jline-pushback-reader (atom nil))

(defn make-reader []
  (let [reader (ConsoleReader.)
        home (System/getProperty "user.home")
        history (FileHistory. (File. home ".jline-reply.history"))
        completer (jline.completion/make-completer reply.initialization/eval-in-user-ns #())]
    (.setBlinkMatchingParen (.getKeys reader) true)
    (doto reader
      (.setHistory history)
      (.setExpandEvents false)
      (.setPaginationEnabled true)
      (.addCompleter completer))))

(defmulti get-prompt type)
(defmethod get-prompt :default [ns]
  (format "%s=> " ns))
(defmethod get-prompt clojure.lang.Namespace [ns]
  (get-prompt (ns-name ns)))

(def prompt-fn (atom get-prompt))
(defn set-prompt-fn! [f]
  (when f (reset! prompt-fn f)))

(defn set-empty-prompt []
  (.setPrompt
    @jline-reader
    (apply str (repeat (count (@prompt-fn (eval-state/get-ns))) \space))))

(defn setup-reader! []
  (Log/setOutput (PrintStream. (ByteArrayOutputStream.)))
  (reset! jline-reader (make-reader)) ; since construction is side-effect-y
  (reset! jline-pushback-reader ; since this depends on jline-reader
    (CustomizableBufferLineNumberingPushbackReader.
      (JlineInputReader.
        {:jline-reader @jline-reader
         :set-empty-prompt set-empty-prompt})
      1)))

(defn reset-reader []
  (when @jline-reader
    (-> (.getCursorBuffer @jline-reader)
        (.clear))))

(defn resume-reader []
  (when @jline-reader
    (.reset (.getTerminal @jline-reader))
    (.redrawLine @jline-reader)
    (.flush @jline-reader)))

(defn shutdown-reader []
  (when @jline-reader
    (.restore (.getTerminal @jline-reader))))

(defn prepare-for-read [eval-fn ns]
  (when-not @jline-reader (setup-reader!))
  (.flush (.getHistory @jline-reader))
  (.setPrompt @jline-reader (@prompt-fn ns))
  (eval-state/set-ns ns)
  (.removeCompleter @jline-reader (first (.getCompleters @jline-reader)))
  (.addCompleter @jline-reader
    (jline.completion/make-completer
      eval-fn
      (fn []
        (.redrawLine @jline-reader)
        (.flush @jline-reader)))))

(defmacro with-jline-in [& body]
  `(do
    (try
      (prepare-for-read reply.initialization/eval-in-user-ns (eval-state/get-ns))
      (binding [*in* @jline-pushback-reader]
        (Thread/interrupted) ; just to clear the status
        ~@body)
      ; NOTE: this indirection is for wrapped exceptions in 1.3
      (catch Throwable e#
        (if (#{IOException InterruptedException} (type (clojure.main/repl-exception e#)))
          (do (reset-reader) nil)
          (throw e#))))))

(defn read [request-prompt request-exit]
  (with-jline-in
    (clojure.main/repl-read request-prompt request-exit)))

