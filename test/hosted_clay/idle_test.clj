(ns hosted-clay.idle-test
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.idle :as idle]
            [hosted-clay.proxy :as proxy])
  (:import (java.time Duration Instant)))

(deftest stale-ids-selection
  (let [now    (Instant/now)
        old    (.minus now (Duration/ofMinutes 30))
        recent (.minus now (Duration/ofMinutes 5))]
    (testing "a notebook idle past the window is stale"
      (is (= #{"a"} (idle/stale-ids {"a" old} now 25))))

    (testing "a notebook active inside the window is not stale"
      (is (= #{} (idle/stale-ids {"a" recent} now 25))))

    (testing "exactly at the window is stale (the bound is inclusive)"
      (let [edge (.minus now (Duration/ofMinutes 25))]
        (is (= #{"a"} (idle/stale-ids {"a" edge} now 25)))))

    (testing "only the idle ids are selected from a mixed set"
      (is (= #{"a" "c"} (idle/stale-ids {"a" old "b" recent "c" old} now 25))))))

(deftest sweep-suspends-stale-only
  (let [now          (Instant/now)
        disconnected (atom [])]
    (with-redefs [proxy/disconnect-notebook! (fn [id] (swap! disconnected conj id))]

      (testing "suspends notebooks idle past the window, leaves active ones"
        (with-redefs [proxy/activity-snapshot
                      (fn [] {"stale"  (.minus now (Duration/ofMinutes 30))
                              "active" (.minus now (Duration/ofMinutes 2))})]
          (is (= #{"stale"} (idle/sweep! 25)))
          (is (= ["stale"] @disconnected))))

      (testing "nil idle-minutes disables the sweep and does no work"
        (reset! disconnected [])
        (let [looked? (atom false)]
          (with-redefs [proxy/activity-snapshot (fn [] (reset! looked? true) {})]
            (idle/sweep! nil)
            (is (= [] @disconnected))
            (is (false? @looked?))))))))
