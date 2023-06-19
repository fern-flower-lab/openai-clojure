(ns ^:no-doc wkok.openai-clojure.azure
  (:require
   #?(:clj [cheshire.core :as json])
   #?(:clj [clojure.java.io :as io])
   [clojure.string :as s]
   [martian.core :as martian]
   #?(:clj [martian.hato :as martian-http])
   #?(:cljs [martian.cljs-http :as martian-http])
   [wkok.openai-clojure.sse :as sse]))

(def add-authentication-header
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (let [api-key (or (-> ctx :params :wkok.openai-clojure.core/options :api-key)
                              #?(:clj (System/getenv "AZURE_OPENAI_API_KEY")
                                 :cljs ""))]
              (assoc-in ctx [:request :headers "api-key"]
                        api-key)))})

(def override-api-endpoint
  {:name ::override-api-endpoint
   :enter (fn [ctx]
            (update-in ctx [:request :url]
                       (fn [url]
                         (let [endpoint (or (-> ctx :params :wkok.openai-clojure.core/options :api-endpoint)
                                            #?(:clj (System/getenv "AZURE_OPENAI_API_ENDPOINT")
                                               :cljs ""))
                               idx (s/index-of url "/openai")]
                           (str endpoint (subs url idx))))))})

(defn patch-handler
  "Patching azure's handlers to support the same operation-id names as the standard openai api"
  [m]
  (let [patched-completions-create-handler (->  (martian/handler-for  m :completions-create)
                                                (assoc :route-name :create-completion))
        patched-chat-completions-create-handler (->  (martian/handler-for  m :chat-completions-create)
                                                (assoc :route-name :create-chat-completion))
        patched-embeddings-create-handler (->  (martian/handler-for  m :embeddings-create)
                                               (assoc :route-name :create-embedding))


        patched-handlers [patched-completions-create-handler
                          patched-embeddings-create-handler
                          patched-chat-completions-create-handler]]

    (assoc m :handlers patched-handlers)))

(defn load-openai-spec []
  #?(:clj (json/decode (slurp (io/resource "azure_openai.json")) keyword)
     :cljs (js/fetch "openai-clojure/azure_openai.json")))

(def m
  (delay
    (patch-handler
     (martian/bootstrap-openapi "/openai"
                                (load-openai-spec)
                                (update
                                 martian-http/default-opts
                                 :interceptors
                                 #(-> (remove (comp #{martian-http/perform-request}) %)
                                      (concat [add-authentication-header override-api-endpoint sse/perform-sse-capable-request])))))))

(defn patch-params [params]
  {:api-version "2023-05-15"
   :deployment-id (:model params)
   :martian.core/body (dissoc params :model)})
