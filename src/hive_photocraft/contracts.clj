(ns hive-photocraft.contracts
  "JVM Malli declarations for public portable functions; no Malli in .cljc."
  (:require [malli.core :as m]
            [hive-photocraft.schema :as s]
            [hive-photocraft.core]
            [hive-photocraft.json]))

(m/=> hive-photocraft.core/refuse [:=> [:cat keyword? string?] s/Envelope])
(m/=> hive-photocraft.core/command [:=> [:cat s/Catalog :any] s/Envelope])
(m/=> hive-photocraft.core/method [:=> [:cat s/Catalog :any] s/Envelope])
(m/=> hive-photocraft.core/token-valid? [:=> [:cat :any] boolean?])
(m/=> hive-photocraft.core/auth [:=> [:cat :any] s/Envelope])
(m/=> hive-photocraft.core/request [:=> [:cat s/Catalog :any :any :any] s/Envelope])
(m/=> hive-photocraft.core/execute [:=> [:cat s/Catalog :any :any :any] s/Envelope])
(m/=> hive-photocraft.core/frame [:=> [:cat :any] s/Envelope])
(m/=> hive-photocraft.core/reply [:=> [:cat :any] s/Envelope])
(m/=> hive-photocraft.json/encode [:=> [:cat :any] s/Envelope])
(m/=> hive-photocraft.json/decode [:=> [:cat :any] s/Envelope])
