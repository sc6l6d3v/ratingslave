import sbt._

object Dependencies {
  object Versions {
    val Http4sVersion = "0.23.17"
    val Specs2Version = "4.9.3"
    val LogbackVersion = "1.2.3"
    val catsRetryVersion = "1.1.0"
    val log4catsVersion = "2.3.1"
    val fs2Version = "3.4.0"
    val loggingVersion = "3.9.2"
    val mongo4catsVersion = "0.6.6"
    val jsoupVersion = "1.13.1"
    val scalatestVersion = "3.2.2"
    val mongoScalaVersion = "4.8.0"
    val circeVersion      = "0.14.3"
    val catsVersion       = "3.9.0"
    val catsEffectVersion       = "3.4.4"
  }

  object http4s {
    val client = "org.http4s"  %% "http4s-ember-client" % Versions.Http4sVersion
    val server = "org.http4s"  %% "http4s-ember-server" % Versions.Http4sVersion
    val circe  = "org.http4s"  %% "http4s-circe"        % Versions.Http4sVersion
    val dsl =    "org.http4s"  %% "http4s-dsl"          % Versions.Http4sVersion
  }

  object fs2 {
    val core = "co.fs2"        %% "fs2-core"             % Versions.fs2Version
    val io =    "co.fs2"       %% "fs2-io"               % Versions.fs2Version
  }

  object circe {
    val circeCore    = "io.circe" %% "circe-core" % Versions.circeVersion
    val circeParser  = "io.circe" %% "circe-parser" % Versions.circeVersion
    val circeGeneric = "io.circe" %% "circe-generic" % Versions.circeVersion
  }

  lazy val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalatestVersion % Test

  object logback {
    val classic = "ch.qos.logback"            % "logback-classic" % Versions.LogbackVersion
    val logging = "com.typesafe.scala-logging" %% "scala-logging" % Versions.loggingVersion
  }

  object cats {
    val retry = "com.github.cb372" %% "cats-retry"      % Versions.catsRetryVersion
    val log4cats = "org.typelevel" %% "log4cats-slf4j" % Versions.log4catsVersion
    val cats = "org.typelevel" %% "cats-core" % Versions.catsVersion
    val catsEffect = "org.typelevel" %% "cats-effect" % Versions.catsEffectVersion
  }

  object jsoup {
    val base = "org.jsoup"        %  "jsoup"               % Versions.jsoupVersion
  }

  object mongodb {
    val driver = "org.mongodb.scala" %% "mongo-scala-driver" % Versions.mongoScalaVersion
  }

  object mongo4cats {
    val core = "io.github.kirill5k" %% "mongo4cats-core" % Versions.mongo4catsVersion
    val circe = "io.github.kirill5k" %% "mongo4cats-circe" % Versions.mongo4catsVersion
  }
}
