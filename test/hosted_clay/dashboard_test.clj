(ns hosted-clay.dashboard-test
  "The dashboard page is pure (maps in, HTML string out), so the usage meter's
   arithmetic and its disabled-cap branch are testable without a system."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosted-clay.ui.pages.dashboard :as dashboard]
            [hosted-clay.usage :as usage]))

(def user {:users/email "kira@example.com"})

(defn- notebook [awake-hours]
  {:notebooks/id           "nb-1"
   :notebooks/title        "T"
   :notebooks/status       "ready"
   :notebooks/share-token  "tok"
   :notebooks/created-at   "2026-06-24T00:00:00Z"
   :notebooks/usage-month  (usage/current-month)
   :notebooks/awake-seconds (long (* awake-hours 3600))})

(defn- render [nb limit] (dashboard/render user nb "https://clay.test" limit))

(deftest usage-meter-shows-approximate-hours-and-percent
  (let [html (render (notebook 12) 50)]
    (testing "the figure is the rounded hours out of the limit"
      (is (str/includes? html "12 of 50 hours")))
    (testing "the bar is drawn to the right percentage (12/50 = 24%)"
      (is (str/includes? html "24%"))
      (is (str/includes? html "width:24%"))
      (is (str/includes? html "usage-track")))))

(deftest usage-meter-caps-the-bar-at-full
  (let [html (render (notebook 80) 50)]
    (testing "over the limit, the bar clamps to 100% rather than overflowing"
      (is (str/includes? html "100%"))
      (is (str/includes? html "width:100%")))))

(deftest usage-meter-without-a-limit-drops-the-bar
  (let [html (render (notebook 12) nil)]
    (testing "a disabled cap shows hours but no bar and no 'of N' framing"
      (is (str/includes? html "12 hours this month"))
      (is (not (str/includes? html "usage-track")))
      (is (not (str/includes? html "of 50"))))))

(deftest dashboard-reflects-a-suspended-notebook
  (let [html (render (assoc (notebook 5) :notebooks/suspended-at "2026-06-24T00:00:00Z") 50)]
    (testing "the status badge reads 'suspended' and a Resume button is offered"
      (is (str/includes? html "badge--suspended"))
      (is (str/includes? html "suspended"))
      (is (str/includes? html "/resume"))
      (is (str/includes? html "Resume")))
    (testing "an active notebook shows Suspend instead"
      (let [active (render (notebook 5) 50)]
        (is (str/includes? active "/suspend"))
        (is (not (str/includes? active "badge--suspended")))))))

(deftest dashboard-without-a-notebook-omits-the-meter
  (let [html (dashboard/render user nil "https://clay.test" 50)]
    (is (str/includes? html "Create your notebook"))
    (is (not (str/includes? html "usage-track")))))
