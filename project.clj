;; Copyright (c) 2021, Viasat, Inc
;; Licensed under EPL 2.0

;; This is for running in Clojure and for generating codox docs
;; Use shadow-cljs for building/running in ClojureScript

(defproject clj-protocol "1.1.1"
  :description "Declarative Protocols and Binary Formats in ClojureScript"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.866"]]

  :plugins [[lein-codox "0.10.8"]]
  :codox
  {:language :clojurescript
   :source-paths ["src"]
   ;;:namespaces [#"protocol\." #"dhcp\." #"icmp\." #"pcap\."]
   ;;:doc-paths ["docs"]
   :doc-files ["docs/tutorial.md" "docs/examples.md"]
   :output-path "docs/www"
   :metadata {:doc/format :markdown}
   :source-uri "http://github.com/Viasat/clj-protocol/blob/{git-commit}/{filepath}#L{line}"})
