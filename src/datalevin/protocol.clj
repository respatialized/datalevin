(ns datalevin.protocol
  "Shared code of client/server"
  (:require [datalevin.bits :as b]
            [datalevin.constants :as c]
            [datalevin.datom :as d]
            [datalevin.util :as u]
            [cognitect.transit :as transit]
            [taoensso.nippy :as nippy]
            [clojure.stacktrace :as st])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]
           [datalevin.io ByteBufferInputStream ByteBufferOutputStream]
           [datalevin.datom Datom]))

(defn read-nippy-bf
  "Read from a ByteBuffer containing nippy encoded bytes, return a Clojure
  value."
  [^ByteBuffer bf]
  (nippy/fast-thaw (b/get-bytes bf)))

(def transit-read-handlers
  {"datalevin/Datom" (transit/read-handler d/datom-from-reader)})

(defn read-transit-bf
  "Read from a ByteBuffer containing transit+json encoded bytes,
  return a Clojure value. Consumes the entire buffer"
  [^ByteBuffer bf]
  (transit/read (transit/reader (ByteBufferInputStream. bf)
                                :json
                                {:handlers transit-read-handlers})))

(defn write-nippy-bf
  "Write a Clojure value as nippy encoded bytes into a ByteBuffer"
  [^ByteBuffer bf v]
  (b/put-bytes bf (nippy/fast-freeze v)))

(def transit-write-handlers
  {Datom (transit/write-handler
           "datalevin/Datom"
           (fn [^Datom d] [(.-e d) (.-a d) (.-v d) (.-tx d)]))})

(defn write-transit-bf
  "Write a Clojure value as transit+json encoded bytes into a ByteBuffer"
  [^ByteBuffer bf v]
  (transit/write (transit/writer (ByteBufferOutputStream. bf)
                                 :json
                                 {:handlers transit-write-handlers})
                 v))

(defn- write-value-bf
  [bf fmt msg]
  (case (short fmt)
    1 (write-transit-bf bf msg)
    2 (write-nippy-bf bf msg)))

(defn- read-value-bf
  [bf fmt]
  (case (short fmt)
    1 (read-transit-bf bf)
    2 (read-nippy-bf bf)))

(defn write-message-bf
  "Write a message to a ByteBuffer. First byte is format, then four bytes
  length of the whole message (include header), followed by message value"
  ([bf msg]
   (write-message-bf bf msg c/message-format-nippy))
  ([^ByteBuffer bf msg fmt]
   (let [start-pos (.position bf)]
     (.position bf (+ c/message-header-size start-pos))
     (write-value-bf bf fmt msg)
     (let [end-pos (.position bf)]
       (.position bf start-pos)
       (.put bf ^byte (unchecked-byte fmt))
       (.putInt bf (- end-pos start-pos))
       (.position bf end-pos)))))

(defn read-transit-bytes
  "Read transit+json encoded bytes into a Clojure value"
  [^bytes bs]
  (transit/read (transit/reader (ByteArrayInputStream. bs)
                                :json
                                {:handlers transit-read-handlers})))

(defn write-transit-bytes
  "Write a Clojure value as transit+json encoded bytes"
  [v]
  (let [baos (ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json
                                   {:handlers transit-write-handlers})
                   v)
    (.toByteArray baos)))

(defn read-value
  [fmt bs]
  (case (short fmt)
    1 (read-transit-bytes bs)
    2 (nippy/fast-thaw bs)))

(defn send-ch
  "Send to socket channel, return the number of bytes sent. return -1 if
  something is wrong"
  [^SocketChannel ch ^ByteBuffer bf]
  (try
    (.write ch bf)
    (catch Exception e
      (st/print-stack-trace e)
      -1)))

(defn send-all
  "Send all data in buffer to channel, will block if channel is busy.
  Close the channel and raise exception if something is wrong"
  [^SocketChannel ch ^ByteBuffer bf ]
  (loop []
    (when (.hasRemaining bf)
      (if (= (send-ch ch bf) -1)
        (do (.close ch)
            (u/raise "Socket channel is closed." {}))
        (recur)))))

(defn write-message-blocking
  "Write a message in blocking mode"
  [^SocketChannel ch ^ByteBuffer bf msg]
  (locking bf
    (.clear bf)
    (write-message-bf bf msg)
    (.flip bf)
    (send-all ch bf)))

(defn receive-one-message
  "Consume one message from the read-bf and return it.
  If there is not enough data for one message, return nil. Prepare the
  buffer for write. If one message is bigger than read-bf, allocate a
  new read-bf. Return `[msg read-bf]`"
  [^ByteBuffer read-bf]
  (let [pos (.position read-bf)]
    (if (> pos c/message-header-size)
      (do (.flip read-bf)
          (let [available (.limit read-bf)
                fmt       (.get read-bf)
                length    ^int (.getInt read-bf)
                read-bf   (if (< (.capacity read-bf) length)
                            (let [^ByteBuffer bf
                                  (ByteBuffer/allocateDirect
                                    (* c/+buffer-grow-factor+ length))]
                              (.rewind read-bf)
                              (b/buffer-transfer read-bf bf)
                              bf)
                            read-bf)]
            (if (< available length)
              (do (doto read-bf
                    (.limit (.capacity read-bf))
                    (.position pos))
                  [nil read-bf])
              (let [ba  (byte-array (- length c/message-header-size))
                    _   (.get read-bf ba)
                    msg (read-value fmt ba)]
                (if (= available length)
                  (.clear read-bf)
                  (doto read-bf
                    (.position length)
                    (.compact)))
                [msg read-bf]))))
      [nil read-bf])))

(defn read-ch
  "Read from the socket channel, return the number of bytes read. Return -1
  if something is wrong"
  [^SocketChannel ch ^ByteBuffer bf]
  (try
    (.read ch bf)
    (catch Exception e
      (st/print-stack-trace e)
      -1)))

(defn receive-ch
  "Receive one message from channel and put it in buffer, will block
  until one full message is received. When buffer is too small for a
  message, a new buffer is allocated. Return [msg bf]."
  [^SocketChannel ch ^ByteBuffer bf]
  (loop [^ByteBuffer bf bf]
    (if (> (.position bf) c/message-header-size)
      (let [[msg ^ByteBuffer bf] (receive-one-message bf)]
        (if msg
          [msg bf]
          (let [^int readn (read-ch ch bf)]
            (cond
              (> readn 0)  (let [[msg bf] (receive-one-message bf)]
                             (if msg [msg bf] (recur bf)))
              (= readn 0)  (recur bf)
              (= readn -1) (do (.close ch)
                               (u/raise "Socket channel is closed." {}))))))
      (let [^int readn (read-ch ch bf)]
        (cond
          (> readn 0)  (let [[msg bf] (receive-one-message bf)]
                         (if msg [msg bf] (recur bf)))
          (= readn 0)  (recur bf)
          (= readn -1) (do (.close ch)
                           (u/raise "Socket channel is closed." {})))))))

(defn extract-message
  "Segment the content of read buffer to extract a message and call msg-handler
  on it. The message is a byte array. Message parsing will be done in the
  msg-handler. In non-blocking mode, it should be handled by a worker thread,
  so the main event loop is not hindered by slow parsing. Assume the message
  is small enough for the buffer."
  [^ByteBuffer read-bf msg-handler]
  (let [pos (.position read-bf)]
    (when (> pos c/message-header-size)
      (.flip read-bf)
      (let [available (.limit read-bf)
            fmt       (.get read-bf)
            length    (.getInt read-bf)]
        (if (< available length)
          (doto read-bf
            (.limit (.capacity read-bf))
            (.position pos))
          (let [ba (byte-array (- length c/message-header-size))]
            (.get read-bf ba)
            (msg-handler fmt ba)
            (if (= available length)
              (.clear read-bf)
              (.compact read-bf))))))))
