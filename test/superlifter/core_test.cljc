(ns superlifter.core-test
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is async]])
            [superlifter.core :as s]
            [urania.core :as u]
            [promesa.core :as prom]
            #?(:clj [superlifter.logging :refer [log]]
               :cljs [superlifter.logging :refer-macros [log]]))
  (:refer-clojure :exclude [resolve]))

#?(:cljs (def Exception js/Error))

#?(:clj
   (defmacro async [done-sym & body]
     `(let [finished# (promise)
            ~done-sym (fn [] (deliver finished# true))]
        ~@body
        (is (deref finished# 5000 false) "test timed out"))))

(defn- fetchable [v]
  (let [fetched? (atom false)]
    (with-meta
      (reify u/DataSource
        (u/-identity [this] v)
        (u/-fetch [this _]
          (log :info "Fetching" v)
          (prom/create (fn [resolve _reject]
                         (log :info "Delivering promise for " v)
                         (reset! fetched? true)
                         (resolve v)))))
      {:fetched? fetched?})))

(defn- fetched? [& fetchables]
  (every? true? (map (comp deref :fetched? meta) fetchables)))

(deftest callback-trigger-test
  (async
   done
   (testing "Callback trigger mode means fetch must be run manually"
     (let [s (s/start! {})
           foo (fetchable :foo)
           bar (fetchable :bar)
           foo-promise (s/enqueue! s foo)
           bar-promise (s/enqueue! s bar)]

       #?(:clj (do (is (not (prom/resolved? foo-promise)))
                   (is (not (prom/resolved? bar-promise)))))
       (is (not (fetched? foo bar)))

       (prom/then (prom/all [(s/fetch! s) foo-promise bar-promise])
                  (fn [[v foo-v bar-v]]

                    (is (= [:foo :bar] v))
                    (is (= :foo foo-v))
                    (is (= :bar bar-v))

                    (is (fetched? foo bar))
                    (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref)))

                    (done)))))))

(deftest interval-trigger-test
  (async
   done
   (testing "Interval trigger mode means the fetch is run every n millis"
     (let [s (s/start! {:buckets {:default {:triggers {:interval {:interval 100}}}}})
           foo (fetchable :foo)
           bar (fetchable :bar)
           foo-promise (s/enqueue! s foo)
           bar-promise (s/enqueue! s bar)]

       #?(:clj (do (is (not (prom/resolved? foo-promise)))
                   (is (not (prom/resolved? bar-promise)))))
       (is (not (fetched? foo bar)))

       (testing "within the next 100ms the fetch should be triggered"
         (prom/then (prom/all [foo-promise bar-promise])
                    (fn [[foo-v bar-v]]
                      (is (= :foo foo-v))
                      (is (= :bar bar-v))

                      (is (fetched? foo bar))
                      (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref)))

                      (done))))))))

(deftest debounced-trigger-test
  (async
   done
   (testing "Debounced trigger mode means the fetch is run after no fetches are scheduled for n millis"
     (let [s (s/start! {:buckets {:default {:triggers {:debounced {:interval 100}}}}})
           foo (fetchable :foo)
           bar (fetchable :bar)
           baz (fetchable :baz)
           foo-promise (s/enqueue! s foo)]

       #?(:clj (is (not (prom/resolved? foo-promise))))
       (is (not (fetched? foo bar baz)))

       #?(:clj (do (Thread/sleep 70)
                   (s/enqueue! s bar)
                   (Thread/sleep 70)
                   (s/enqueue! s baz))
          :cljs (do (js/setTimeout s/enqueue! 70 s bar)
                    (js/setTimeout s/enqueue! 140 s baz)))

       (is (not (fetched? foo bar baz)))

       (testing "within the next 100ms the fetch should be triggered"
         (prom/then foo-promise
                    (fn [foo-v]
                      (is (= :foo foo-v))

                      (is (fetched? foo bar baz))
                      (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref)))

                      (done))))))))

(deftest queue-size-trigger-test
  (async
   done
   (testing "Queue size trigger mode means the fetch is run when queue size reaches n"
     (let [s (s/start! {:buckets {:default {:triggers {:queue-size {:threshold 2}}}}})
           foo (fetchable :foo)
           bar (fetchable :bar)
           foo-promise (s/enqueue! s foo)]

       (testing "not triggered when queue size below threshold"
         #?(:clj (is (not (prom/resolved? foo-promise))))
         (is (not (fetched? foo bar))))

       (testing "when the queue size reaches 2 the fetch is triggered"
         (let [bar-promise (s/enqueue! s bar)]
           (prom/then (prom/all [foo-promise bar-promise])
                      (fn [[foo-v bar-v]]
                        (is (= :foo foo-v))
                        (is (= :bar bar-v))

                        (is (fetched? foo bar))
                        (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref)))

                        (done)))))))))

(deftest multi-buckets-test
  (async
   done
   (let [s (s/start! {:buckets {:default {:triggers {:queue-size {:threshold 1}}}
                                :ten {:triggers {:queue-size {:threshold 10}}}}})
         foo (fetchable :foo)
         bars (repeatedly 10 #(fetchable :bar))]

     (testing "default queue fetched immediately"
       (let [foo-promise (s/enqueue! s foo)]
         (prom/then
          foo-promise
          (fn [foo-v]
            (is (= :foo foo-v))
            (is (fetched? foo))

            (testing "ten queue not fetched until queue size is 10"
              (let [first-bar-promise (s/enqueue! s :ten (first bars))]
                #?(:clj (is (not (prom/resolved? first-bar-promise))))
                (is (not (fetched? (first bars))))

                (let [rest-bar-promises (mapv #(s/enqueue! s :ten %) (rest bars))]
                  (prom/then
                   (prom/all (cons first-bar-promise rest-bar-promises))
                   (fn [bar-vs]
                     (is (every? #(= :bar %) bar-vs))
                     ;; only the first bar is fetched because urania deduped them
                     (is (fetched? (first bars)))

                     (testing "adding an adhoc bucket"
                       (s/add-bucket! s :pairs {:triggers {:queue-size {:threshold 2}}})
                       (let [h1 (fetchable 1)
                             h2 (fetchable 2)
                             h1-promise (s/enqueue! s :pairs h1)]
                         #?(:clj (is (not (prom/resolved? h1-promise))))
                         (is (not (fetched? h1)))

                         (let [h2-promise (s/enqueue! s :pairs h2)]
                           (prom/then (prom/all [h1-promise h2-promise])
                                      (fn [[h1-v h2-v]]
                                        (is (= 1 h1-v))
                                        (is (= 2 h2-v))
                                        (is (fetched? h1 h2))

                                        (s/stop! s)

                                        (done))))))))))))))))))

(deftest cache-test
  (async
   done
   (testing "The cache is shared across fetches and prevents dupe calls being made"
     (let [cache (atom {})
           s (s/start! {:buckets {:default {}}
                        :urania-opts {:cache cache}})
           foo (fetchable :foo)
           bar (fetchable :bar)
           foo-promise (s/enqueue! s foo)
           bar-promise (s/enqueue! s bar)]

       (prom/then
        (prom/all [(s/fetch-all! s) foo-promise bar-promise])
        (fn [[v foo-v bar-v]]
          (is (= [:foo :bar] v))

          (is (= [:foo :bar] [foo-v bar-v]))
          (is (fetched? foo bar))

          (let [foo-2 (fetchable :foo)
                foo-2-promise (s/enqueue! s foo-2)]
            (prom/then (prom/all [(s/fetch-all! s) foo-2-promise])
                       (fn [[v-2 foo-2-v]]
                         (is (= [:foo] v-2))
                         (is (= :foo foo-2-v))
                         (is (not (fetched? foo-2)))

                         (s/stop! s)

                         (done))))))))))

(deftest fetch-failure-test
  (async
   done
   (let [s (s/start! {})
         foo (reify u/DataSource
               (u/-identity [this] :foo)
               (u/-fetch [this _]
                 (prom/create (fn [_resolve reject]
                                (reject (ex-info "I blew up!" {}))))))
         foo-promise (s/enqueue! s foo)]

     (prom/catch (s/fetch! s)
         (fn [v]
           (is (instance? Exception v))
           #?(:clj (do (is (not (prom/resolved? foo-promise)))
                       ;; should this be true?
                       ;; (is (prom/rejected? foo-promise))
                       ))
           (done))))))
