(ns hive-photocraft.schema
  "JVM value-object contracts for the portable PhotoCraft vocabulary.")

(def ErrorValue [:map [:kind keyword?] [:hint string?]])
(def Envelope [:or [:map [:ok :any]] [:map [:error ErrorValue]]])
(def CatalogEntry [:map [:id string?] [:label string?] [:params-doc string?] [:source string?]])
(def Catalog [:map [:commands [:vector CatalogEntry]] [:methods [:vector string?]]])
(def Request [:map ["id" [:or string? nat-int?]] ["method" string?] ["params" map?]])
(def Config [:map [:transport {:optional true} :any]])
(def ToolInput [:map ["command" string?]])
