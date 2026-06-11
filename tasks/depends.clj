(ns depends
  "Helpers for declaring bb-task dependencies.

   Pure functions return result maps with the shape
   `{:lines [...] :exit int}` (where int is non-zero and `:lines`
   contains a message to print) for failure or `{:exit 0}` for
   success. `say!` is the only side-effecting function, printing
   `:lines` (if any) and exiting when `:exit` is non-zero."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn problem
  ([msg]     (problem msg nil))
  ([msg fix] {:lines (cond-> [(str "✗ " msg)]
                       fix (conj (str "  → " fix)))
              :exit  1}))

(def ok {:exit 0})

(defn cli-missing
  "Result map for an executable presence check."
  [exe install-url]
  (if (fs/which exe)
    ok
    (problem (str exe " is not installed")
             (str "Install: " install-url))))

(defn say!
  ([report] (say! report println #(System/exit %)))
  ([{:keys [lines exit]} print-fn exit-fn]
   (run! print-fn lines)
   (when (and exit (not (zero? exit)))
     (exit-fn exit))))

(defn require-cli!
  [exe install-url]
  (say! (cli-missing exe install-url)))

(defn sh!
  "Run a shell command, inheriting stdio and propagating the exit code
   without a stack trace."
  [& args]
  (let [{:keys [exit]} (apply p/shell {:continue true} args)]
    (when-not (zero? exit)
      (System/exit exit))))
