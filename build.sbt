name := "simbacoin-mlm-backend"

version := "1.0"

scalaVersion := "2.12.7"

lazy val akkaVersion = "2.5.26"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.1.9",
  "com.typesafe.akka" %% "akka-http-jackson" % "10.1.9",
  "com.typesafe.akka" %% "akka-http-caching" % "10.1.9",
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe" %% "ssl-config-akka" % "0.2.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.lightbend.akka" %% "akka-persistence-couchbase" % "1.0",
  "com.google.protobuf" % "protobuf-java" % "3.9.1", 
  "com.google.firebase" % "firebase-admin" % "6.9.0",
  "io.kamon" % "sigar-loader" % "1.6.6-rev002",
  "org.bouncycastle" % "bcprov-jdk16" % "1.46",
  "io.netty" % "netty-all" % "4.1.39.Final",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "junit" % "junit" % "4.12")
