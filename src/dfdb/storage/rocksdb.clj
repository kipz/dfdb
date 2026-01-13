(ns dfdb.storage.rocksdb
  "RocksDB storage backend implementation."
  (:require [dfdb.storage :as storage]
            [dfdb.storage.codec :as codec])
  (:import [org.rocksdb RocksDB Options WriteBatch WriteOptions RocksIterator ReadOptions Snapshot
            CompressionType BlockBasedTableConfig]))

(set! *warn-on-reflection* true)

;; Load RocksDB native library
(RocksDB/loadLibrary)

(defn- create-options
  "Create RocksDB Options from config map."
  ^Options [{:keys [create-if-missing
                    compression
                    write-buffer-size
                    max-open-files
                    block-cache-size]
             :or {create-if-missing true
                  compression :snappy
                  write-buffer-size (* 64 1024 1024)  ; 64MB
                  max-open-files -1  ; unlimited
                  block-cache-size (* 128 1024 1024)}}]  ; 128MB
  (let [opts (Options.)]
    (.setCreateIfMissing opts create-if-missing)

    ;; Compression
    (.setCompressionType opts
                         (case compression
                           :none CompressionType/NO_COMPRESSION
                           :snappy CompressionType/SNAPPY_COMPRESSION
                           :lz4 CompressionType/LZ4_COMPRESSION
                           :zstd CompressionType/ZSTD_COMPRESSION
                           CompressionType/SNAPPY_COMPRESSION))

    ;; Memory and performance
    (.setWriteBufferSize opts write-buffer-size)
    (.setMaxOpenFiles opts max-open-files)

    ;; Block cache for reads
    (when block-cache-size
      (let [table-config (BlockBasedTableConfig.)]
        (.setBlockCacheSize table-config block-cache-size)
        (.setTableFormatConfig opts table-config)))

    opts))

(deftype RocksDBStorage [^RocksDB db ^String path ^Options options ^:volatile-mutable closed?]
  storage/Storage
  (put [this key value]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [k (codec/encode-key key)
          v (codec/encode-value value)]
      (.put db k v)
      this))

  (get-value [this key]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [k (codec/encode-key key)
          v-bytes (.get db k)]
      (when v-bytes
        (codec/decode-value v-bytes))))

  (scan [this start-key end-key]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [start-bytes (codec/encode-key start-key)
          end-bytes (codec/encode-key end-key)]
      (with-open [iter ^RocksIterator (.newIterator db)]
        (.seek iter start-bytes)
        (loop [results []]
          (if (and (.isValid iter)
                   (neg? (java.util.Arrays/compareUnsigned (.key iter) end-bytes)))
            (let [k (codec/decode-key (.key iter))
                  v (codec/decode-value (.value iter))]
              (.next iter)
              (recur (conj results [k v])))
            results)))))

  (delete [this key]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [k (codec/encode-key key)]
      (.delete db k)
      this))

  (batch-write [this ops]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (with-open [batch (WriteBatch.)
                write-opts (WriteOptions.)]
      (doseq [op ops]
        (case (first op)
          :put
          (let [k (codec/encode-key (second op))
                v (codec/encode-value (nth op 2))]
            (.put batch k v))

          :delete
          (let [k (codec/encode-key (second op))]
            (.delete batch k))))
      (.write db write-opts batch))
    this)

  storage/StorageLifecycle
  (close [this]
    (when-not closed?
      (set! closed? true)
      (when options
        (.close options))
      (when db
        (.close db)))
    nil)

  (snapshot [this]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [snapshot (.getSnapshot db)]
      ;; Return snapshot as opaque object
      ;; In a full implementation, you'd track snapshots and allow reads from them
      snapshot))

  (restore-snapshot [this snapshot-id]
    ;; RocksDB snapshots are more complex - this is a simplified stub
    ;; Full implementation would require tracking snapshots and creating readers with snapshot options
    (throw (ex-info "restore-snapshot not fully implemented for RocksDB"
                    {:snapshot-id snapshot-id
                     :note "Use snapshot handles with ReadOptions for point-in-time reads"})))

  (compact [this]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    ;; Compact entire database
    (.compactRange db (byte-array 0) (byte-array 0))
    nil)

  storage/StreamingStorage
  (scan-stream [this start-key end-key opts]
    (when closed?
      (throw (ex-info "Storage is closed" {:path path})))
    (let [start-bytes (codec/encode-key start-key)
          end-bytes (codec/encode-key end-key)
          iter ^RocksIterator (.newIterator db)]
      (.seek iter start-bytes)
      ((fn step []
         (lazy-seq
          (when (and (.isValid iter)
                     (neg? (java.util.Arrays/compareUnsigned (.key iter) end-bytes)))
            (let [k (codec/decode-key (.key iter))
                  v (codec/decode-value (.value iter))]
              (.next iter)
              (cons [k v] (step))))))))))

(defn create-rocksdb-storage
  "Create RocksDB storage backend.

  Config options:
    :path - Directory path for RocksDB data (required)
    :create-if-missing - Create database if it doesn't exist (default: true)
    :compression - Compression type: :none, :snappy, :lz4, :zstd (default: :snappy)
    :write-buffer-size - Write buffer size in bytes (default: 64MB)
    :max-open-files - Max open files (-1 for unlimited) (default: -1)
    :block-cache-size - Block cache size for reads in bytes (default: 128MB)

  Example:
    (create-rocksdb-storage {:path \"/var/lib/dfdb/data\"
                             :compression :snappy})"
  [{:keys [path] :as config}]
  (when-not path
    (throw (ex-info "RocksDB storage requires :path in config" {:config config})))

  (let [options (create-options config)
        db (RocksDB/open options path)]
    (RocksDBStorage. db path options false)))

(defn destroy-rocksdb-storage
  "Destroy RocksDB storage at path. WARNING: This deletes all data!

  Useful for testing or complete data removal."
  [path]
  (with-open [opts (Options.)]
    (RocksDB/destroyDB path opts)))
