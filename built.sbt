name := "PartyBoard"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.11.7"

routesGenerator := InjectedRoutesGenerator

libraryDependencies ++= Seq(
    "org.reactivemongo" %% "reactivemongo" % "0.11.7",
    "org.reactivemongo" %% "play2-reactivemongo" % "0.11.7.play24"
)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

lazy val root = (project in file(".")).enablePlugins(PlayScala)
