;; Tests for organizing transposon insertion sites

(ns hbc.transposon.test.merge
  (:use [hbc.transposon.merge]
        [midje.sweet])
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io StringReader StringWriter]))

(fact "Collapse scores from multiple positions into single representation."
  (let [data [[{:space "chr1" :pos 10 :count 2 :seq "GATC" :exp "one"}
               {:space "chr1" :pos 19 :count 1 :seq "ATCG" :exp "one"}
               {:space "chr1" :pos 17 :count 2 :seq "GATC" :exp "two"}]
              [{:space "chr1" :pos 40 :count 1 :seq "CCCC" :exp "one"}]]
        combo (combine-locations data)
        out (output-combined "" ["one" "two"] combo)]
    (count combo) => 2
    (count (first combo)) => 2
    (count (second combo)) => 1
    (get (first combo) "one") => {:space "chr1" :pos #{10 17 19} :count 3 :seq #{"GATC" "ATCG"}}
    (.toString out) => "chr,pos,one,two,seq\nchr1,10;17;19,3,2,GATC;ATCG\nchr1,40,1,0,CCCC\n")
  (against-background
    (io/writer anything) => (StringWriter.)))

(fact "Combine multiple locations based on nearby positions."
  (let [data [{:space "chr1" :pos 10} {:space "chr1" :pos 19} {:space "chr1" :pos 29}
              {:space "chr1" :pos 40} {:space "chr2" :pos 10} {:space "chr3" :pos 50}
              {:space "chr3" :pos 30} {:space "chr3" :pos 55}]
        config {:algorithm {:distance 10}}
        groups (combine-by-position data config)]
    (count groups) => 5
    (count (first groups)) => 3
    (count (second groups)) => 1
    (nth groups 2) => [{:space "chr2" :pos 10}]
    (last groups) => [{:space "chr3" :pos 50} {:space "chr3" :pos 55}]))

(fact "Read locations from custom tab-delimited file."
  (let [data (read-custom-positions "")]
    (first data) => {:strand "+" :space "chr1" :pos 98328731 :count 32
                     :seq "TAGGTCTAGAGAAGTAGCTGAGTGTTCTATATCCAGATCATCAG"}
    (-> data second :pos) => 95178391
    (count data) => 2)
  (against-background
    (io/reader anything) => (StringReader. (string/join "\n"
    ["HWI-ST782:118:C07NCACXX:7:2308:6744:99171 1:N:0:TAGCTT\t+\tchr1\t98328731\tTAGGTCTAGAGAAGTAGCTGAGTGTTCTATATCCAGATCATCAG\tEAH4.77?ECC>@C?;;@((66.5;B@CCA>>3;,55:?5>@A5\t0\t32\t98328731\tTAGGTCTAGAGAAGTAGCTGAGTGTTCTATATCCAGATCATCAG"
     "HWI-ST782:118:C07NCACXX:7:1305:13969:197330 1:N:0:TAGCTT\t-\tchr1\t95178347\tATGTCCCTGAGGGCCTTCCTTGGCCGTCTGGTCTCCTTGGTACC\tDDDDDDDDDDDDDDDDDDDDDDDDDFHHHHJJIJJJJJJIGFIJ\t0\t368\t95178391\tGGTACCAAGGAGACCAGACGGCCAAGGAAGGCCCTCAGGGACAT"]))))
