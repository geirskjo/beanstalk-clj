(ns beanstalk-clj.core-test
  (:require [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clojure.string :as string]
            [beanstalk-clj.core :refer :all]))

(defmacro beanstalk [& body] `(with-beanstalkd (beanstalkd-factory)  ~@body))

(defn slingshot-exception-class
  "Return the best guess at a slingshot exception class."
  []
  (try
    (Class/forName "slingshot.Stone")
    (catch Exception _
      (let [ei (Class/forName "slingshot.ExceptionInfo")]
        (if (and (resolve 'clojure.core/ex-info)
                 (resolve 'clojure.core/ex-data))
          (Class/forName "clojure.lang.ExceptionInfo")
          ei)))))

(defmacro is-thrown+?
  "clojure.test clause for testing that a slingshot exception is thrown."
  [& body]
  `(is (~'thrown? ~(slingshot-exception-class) ~@body)))

(defmacro is-thrown-with-msg+?
  "clojure.test clause for testing that a slingshot exception is thrown."
  [& body]
  `(is (~'thrown-with-msg? ~(slingshot-exception-class) ~@body)))

; Basic Operation

(deftest enqueue-jobs
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)]
    (testing "Put body (with default options)"
      (let [jid (put producer "body")]
        (is (= (number? jid)))
        (let [job (reserve consumer)]
          (delete job))))
    (testing "Put too big body")
    (testing "Put non-str body"
      (is-thrown+?
       {:type :type-error :message "Job body must be a String"}
       (put producer 1234)))
    (.close producer)
    (.close consumer))

  (testing "put and delete operation with beanstalkd"
    (with-beanstalkd (beanstalkd-factory)
      (put "body"))
    (with-beanstalkd (beanstalkd-factory)
      (let [job (reserve)]
        (is (= "body" (.body job)))
        (delete job))))
  (testing "put non-str body in with statement"
    (with-beanstalkd (beanstalkd-factory)
      (is-thrown+? {:type :type-error :message "Job body must be a String"}
                   (put 1234)))))

(deftest put-reserve-delete
  (defn asserts [timeout]
    (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)]
      (testing "Reserve with timeout"
       (let [_ (put producer "body")
             job (reserve consumer :with-timeout timeout)]
         (is (.reserved? job))
         (is (number? (.jid job)))
         (is (= (.length job) (.length "body")))
         (is (= (.body job) "body"))
         (delete job)
         ))))
  (asserts nil)
  (asserts 0)
  (asserts 1))

(deftest reserve-with-timeout-return-nil-imediately
  (testing "If you use a timeout of 0, reserve will immediately return either a job or None"
    (let [consumer (beanstalkd-factory)]
      (is (nil? (reserve consumer :with-timeout 0)))))
  (testing "with-statement"
    (with-beanstalkd (beanstalkd-factory)
      (is (nil? (reserve :with-timeout 0))))))

; Tube Management

(deftest test-tubes

  (testing "Watch tube"
    (let [bt (beanstalkd-factory)]
      (is (= (number? (first (watch-tube bt "default")))))))
  (testing "watch tube in with-statement"
    (with-beanstalkd (beanstalkd-factory)
      (is (= (number? (first (watch-tube "default")))))))

  (let [bt (beanstalkd-factory)]
    (testing "List default tubes"
      (is (= ["default"] (list-tubes bt)))
      (with-beanstalkd (beanstalkd-factory)
        (is (= ["default"] (list-tubes)))))

    (testing "List default tube used"
      (is (= "default" (list-tube-used bt)))
      (with-beanstalkd (beanstalkd-factory)
        (is (= "default" (list-tube-used)))))


    (testing "Get using"
      (is (= "default" (using bt)))
      (with-beanstalkd (beanstalkd-factory)
        (is (= "default" (using)))))


    (let [_ (use-tube bt "test-tube")]
      (testing "List available tubes"
        (is (= ["default" "test-tube"] (list-tubes bt)))
        (with-beanstalkd (beanstalkd-factory)
          (is (= ["default" "test-tube"] (list-tubes)))))

      (testing "List tube used"
        (is (= "test-tube" (list-tube-used bt))))

      (testing "Get using"
        (is (= "test-tube" (using bt)))))

    (let [_ (watch-tube bt "test-tube")]
      (testing "List watching tubes"
        (is (= ["default" "test-tube"] (watching bt))))

      (testing "List watching tubes"
        (is (= ["default" "test-tube"] (list-tubes-watched bt))))

      (testing "Ignore tube"
        (let [_ (ignore bt "test-tube")]
          (is (= ["default"] (watching bt)))
          (is-thrown+? {:type :command-failure, :status "NOT_IGNORED", :results nil}
                      (ignore bt "default")))))))


; Statistics

(deftest beanstalk-statistics

  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)
        jid (put producer "body")
        job (reserve consumer)
        stats-map (stats-job job)]
    (testing "stats"
      (is (= "default" (:tube stats-map)))
      (is (= "reserved" (:state stats-map))))

    (delete job)

    (testing "command failed on stats deleted job"
      (is-thrown+? {:type :command-failure,
                    :status "NOT_FOUND",
                    :results []}
                   (stats-job job)))

    (testing "stats default tube"
      (let [s (stats-tube consumer "default")]
        (is (= "default" (:name s)))))

    (testing "server-level statistics"
      (let [s (stats-beanstalkd consumer)]
        (is (= 0 (:current-jobs-reserved s)))))
    ))

(deftest stats-in-with
  (testing "server-level statistics in with-statement"
    (with-beanstalkd (beanstalkd-factory)
      (is (= 0 (:current-jobs-reserved (stats-beanstalkd)))))))

(deftest a-job-with-a-delay
  (testing "a job with a delay will only be available for reservation once this delay passed"
    (let [producer (beanstalkd-factory)
          consumer (beanstalkd-factory)
          jid (put producer "body" :delay 1)
          reserve-imediately (reserve consumer :with-timeout 0)
          job (reserve consumer :with-timeout 1)]
      (is (nil? reserve-imediately))
      (is (= "body" (.body job)))
      (is (= "reserved" (:state (stats-job job))))
      (delete job))))

(deftest release-job
  (testing "release the job back to the tube it came from"
    (let [producer (beanstalkd-factory)
          consumer (beanstalkd-factory)
          jid (put producer "body")
          job (reserve consumer)
          ]
      (release job)
      (is (= "ready" (:state (stats-job job))))
      (delete job))))

(deftest bury-and-kick
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)
        jid (put producer "body")
        job (reserve consumer)
        ]
    (testing "bury job"
      (bury job)
      (is (= "buried" (:state (stats-job job))))
      (is (nil? (reserve consumer :with-timeout 0))))
    (testing "kick job"
      (kick job)
      (is (= "ready" (:state (stats-job job)))))
    (delete consumer jid)))

(deftest inspecting-jobs
  ; TODO: test peek-delayed
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)
        jid (put producer "body")]
    (testing "peeking non-existing job"
      (is-thrown+?
       {:type :command-failure, :status "NOT_FOUND", :results nil}
       (peek consumer "00000000000000")))

    (testing "peek did not reserve the job"
      (let [job (peek consumer jid)]
        (is (= "body" (. job body)))
        (is (= "ready" (:state (stats-job job))))))

    (testing "peek ready"
      (let [job (peek-ready consumer)]
        (is (= jid (.jid job)))
        (is-thrown+?
          {:type :command-failure, :status "NOT_FOUND", :results nil}
          (release job))
        (is (= "ready" (:state (stats-job job))))

        (delete job)
        (is-thrown+?
          {:type :command-failure, :status "NOT_FOUND", :results nil}
          (stats-job job))))))

(deftest job-priorities
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)
        jid42 (put producer "priority 42" :priority 42)
        jid21 (put producer "priority 21" :priority 21)
        jid21twice (put producer "priority 21 twice" :priority 21)
        ]
    (is (= "priority 21" (. (reserve consumer) body)))
    (delete consumer jid21)
    (is (= "priority 21 twice" (. (reserve consumer) body)))
    (delete consumer jid21twice)
    (is (= "priority 42" (. (reserve consumer) body)))
    (delete consumer jid42)))

(deftest job-op-in-with
  (beanstalk
   (testing "stats"
     (let [jid (put "hey")
           job (reserve)]
       (is (= 120 (:ttr (stats-job jid))))
       (is (= 120 (:ttr (stats-job job))))
       (delete job)))
   (testing "release"
     (let [jid (put "hey")
           job (reserve)]
       (release job)
       (is (= "ready" (:state (stats-job jid))))
       (reserve)
       (is (= "reserved" (:state (stats-job jid))))
       (release jid)
       (is (= "ready" (:state (stats-job jid))))
       (reserve)
       (delete job)))
   (testing "delete job"
     (let [jid (put "delete")
           job (reserve)]
       (delete job)
       (is-thrown+?
        {:type :command-failure, :status "NOT_FOUND", :results nil}
        (stats-job jid))))
   (testing "delete jid"
     (let [jid (put "delete jid")]
       (delete jid)
       (is-thrown+?
        {:type :command-failure, :status "NOT_FOUND", :results nil}
        (stats-job jid))))
   (testing "bury && kick"
     (let [jid (put "bury && kick")
           job (reserve)]
       (bury jid)
       (is (= "buried" (:state (stats-job job))))
       (is (= "bury && kick" (.body (peek-buried))))
       (kick 1)
       (is (= "ready" (:state (stats-job job))))
       (reserve)
       (delete jid)))))

(deftest with-statement-return-value
  (testing "return"
    (is (number? (with-beanstalkd (beanstalkd-factory)
      (let [jid (put "return")
            job (reserve)]
        (delete jid)
        jid))))))
