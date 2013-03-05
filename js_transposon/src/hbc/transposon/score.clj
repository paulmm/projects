;; Assess reliability of merged insertions sites by normalizing and
;; assigning quality scores.

(ns hbc.transposon.score
  (:use [incanter.io :only [read-dataset]])
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [cli]]
            [incanter.core :as icore]
            [incanter.stats :as stats]
            [me.raynes.fs :as fs]
            [lonocloud.synthread :as ->]
            [hbc.transposon.config :as tconfig]))

;; Columns that are not experimental data
(def ^:dynamic *ignore-cols* #{:chr :pos :seq})

;; ## Dataset statistics

(defn summarize-count-statistics [ds]
  "Summarize statistics of counts in each experiment."
  (letfn [(exp-stats [xs]
            [(stats/mean xs) (stats/median xs) (stats/sd xs)])]
    (map #(cons % (exp-stats (icore/sel ds :cols %)))
         (remove #(contains? *ignore-cols* %) (icore/col-names ds)))))

(defn print-count-stats [ds]
  "Print out count statistics for the given dataset"
  (println "| exp | mean | median | std-dev |")
  (doseq [[x m md sd] (summarize-count-statistics ds)]
    (println (format "| %s | %.3f | %.3f | %.3f |" (name x) m md sd))))

;; ## Dataset normalization
;;
;; Normalize columns by total reads and rows by percentage of maximum
;; to allow experiment and position comparisons.

(defn- get-col-name [col ds ignore config]
  "Retrieve the configured name of a column based on index."
  (let [col-names (remove #(contains? ignore %) (icore/col-names ds))
        exp-names (map :name (:experiments config))]
    (get (zipmap col-names exp-names) col)))

(defn normalize-counts
  "Normalize counts to standard metric based on totals in each experiment.
   By default normalizes to total count in a column scaled to 1 million reads."
  [dataset config & {:keys [base ignore]
                     :or {base 1e6
                          ignore *ignore-cols*}}]
  (letfn [(normalize [x total]
            (if-not (nil? total)
              (* (/ x total) base)
              x))
          (get-col-norm [col ds ignore config]
            (get
             (reduce #(assoc %1 (:name %2) (get %2 :expnorm ""))
                     {} (:experiments config))
             (get-col-name col ds ignore config)))
          (maybe-normalize [ds col]
              (if-not (contains? ignore col)
                (let [col-norm (get-col-norm col ds ignore config)
                      total (case col-norm
                                  "auto" (float (apply + (icore/sel ds :cols col)))
                                  "" nil
                                  (Integer/parseInt col-norm))]
                  (icore/transform-col ds col normalize total))
                ds))]
    (loop [ds dataset
           cols (icore/col-names ds)]
      (if-let [col (first cols)]
        (recur (maybe-normalize ds col) (rest cols))
        ds))))

(defn- process-row [ds row f ignore]
  "Process columns of interest in the current row"
  (let [[same-cols proc-cols] ((juxt filter remove) #(contains? ignore %) (icore/col-names ds))]
    (merge
     (zipmap same-cols (map #(icore/sel ds :rows row :cols %) same-cols))
     (zipmap proc-cols (f (map #(icore/sel ds :rows row :cols %) proc-cols))))))

(defn normalize-pos-ratios
  "Normalize counts as the percentage of the max at a position.
   This normalizes by row, in contrast to normalize-counts
   which handles columns."
  [ds config & {:keys [ignore]
                :or {ignore *ignore-cols*}}]
  (let [conf-ignore (if (= "auto" (get-in config [:algorithm :rownorm] ""))
                      ignore
                      (set (icore/col-names ds)))]
    (letfn [(normalize-row [row]
              (let [cur-max (if-not (empty? row) (apply max row))]
                (map #(/ % cur-max) row)))]
      (icore/dataset (icore/col-names ds)
                     (map #(process-row ds % normalize-row conf-ignore)
                          (range (icore/nrow ds)))))))

; ## Dataset filtration

(defn- filter-by-fn
  "General by-row filter utility based on a evaluator function."
  [filter-fn?]
  (fn [ds config & {:keys [ignore]
                    :or {ignore *ignore-cols*}}]
    (let [cols (remove #(contains? ignore %) (icore/col-names ds))]
      (icore/dataset (icore/col-names ds)
                     (filter (partial filter-fn? config cols)
                             (map #(process-row ds % identity ignore)
                                  (range (icore/nrow ds))))))))

(def filter-by-multiple
  "Require a count to be present in multiple experiments to pass filtering.
   Looks for multiple counts in experimental, not control, samples."
  (letfn [(get-control-cols [config cols]
            (let [names-to-cols (zipmap (map :name (:experiments config)) cols)
                  ctrls (flatten [(get config :controls [])
                                  (map #(get % :controls []) (:experiments config))])]
              (set (map #(get names-to-cols %) ctrls))))
          (is-single-exp? [config cols data]
            (let [ctrl-cols (get-control-cols config cols)
                  remain-cols (remove ctrl-cols cols)]
              (> (count (filter #(> (% data) 0) remain-cols))
                 1)))]
    (filter-by-fn is-single-exp?)))

(def filter-by-controls
  "Filter experiments that have the highest count values in controls.
   Requires one experiment to have a higher count value than control
   to pass."
  (letfn [(get-controls [exp config names-to-cols]
            (let [ctrls (get exp :controls (get config :controls []))]
              (map #(get names-to-cols %) ctrls)))
          (controls-by-exp [cols config]
            (let [names-to-cols (zipmap (map :name (:experiments config)) cols)]
              (zipmap cols (map #(get-controls % config names-to-cols)
                                (:experiments config)))))
          (good-col? [col data exp-controls]
            (if-not (empty? (get exp-controls col []))
              (>= (get data col)
                  (apply max (map #(get data %) (get exp-controls col))))))
          (is-control-dominated? [config cols data]
            (let [exp-controls (controls-by-exp cols config)]
              (->> (map #(good-col? % data exp-controls) cols)
                   (remove nil?)
                   (not-every? false?))))]
    (filter-by-fn is-control-dominated?)))

; ## Top level functionality

(defn normalize-merge [merge-file config-file excel-file]
  "Normalize and prepare statistics on a merged file."
  (let [config (tconfig/do-load (str (fs/parent merge-file)) config-file excel-file)]
    (letfn [(mod-file-name [ext]
              (format "%s-%s" (-> merge-file (string/split #"\.")
                                  reverse
                                  rest
                                  reverse
                                  (#(string/join "." %)))
                               ext))]
      (-> (read-dataset merge-file :header true)
          (normalize-counts config)
          (normalize-pos-ratios config)
          (->/aside ds
            (print-count-stats ds)
            (icore/save ds (mod-file-name "normal.csv")))
          (filter-by-controls config)
          (->/aside ds
            (icore/save ds (mod-file-name "normal-nomultifilter.csv")))
          (filter-by-multiple config)
          (->/aside ds
            (print-count-stats ds)
            (icore/save ds (mod-file-name "normal-filter.csv")))))))

(defn -main [& args]
  (let [[opts [merge-file] help]
        (cli args
             ["-c" "--config" "YAML config file with inputs"]
             ["-x" "--excel" "Excel file with experiment info" :default nil])]
    (if (nil? merge-file)
      (do
        (println "Expect merged CSV file as input.")
        (println "Required: score <merge-csv-file>")
        (println help)
        (System/exit 0))
      (normalize-merge merge-file (:config opts) (:excel opts)))))