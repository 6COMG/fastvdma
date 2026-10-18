// See README.md for license details.

def scalacOptionsVersion(scalaVersion: String): Seq[String] = {
  Seq() ++ {
    // If we're building with Scala > 2.11, enable the compile option
    //  switch to support our anonymous Bundle definitions:
    //  https://github.com/scala/bug/issues/10047
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, scalaMajor: Long)) if scalaMajor < 12 => Seq()
      case _ => Seq("-Xsource:2.13")
    }
  }
}

def javacOptionsVersion(scalaVersion: String): Seq[String] = {
  Seq() ++ {
    // Scala 2.12 requires Java 8. We continue to generate
    //  Java 7 compatible code for Scala 2.11
    //  for compatibility with old clients.
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, scalaMajor: Long)) if scalaMajor < 12 =>
        Seq("-source", "1.7", "-target", "1.7")
      case _ =>
        Seq("-source", "17", "-target", "17")
    }
  }
}

ThisBuild / organization := "Antmicro"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.12"
name := "FastVDMA"

val chiselVersion = "6.2.0"
crossScalaVersions := Seq("2.11.12", "2.13.12")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

// Library name, Organization, Version
val defaultVersions = Map(
  "chisel" -> "org.chipsalliance" -> chiselVersion,
  "chiseltest" -> "edu.berkeley.cs" -> "6.0.0",
  "chisel-iotesters" -> "edu.berkeley.cs" -> "2.5.5+",
  "scalatest" -> "org.scalatest" -> "3.2.16"
)

// Provide a managed dependency on X if -DXVersion="" is supplied on the command line.
libraryDependencies ++= defaultVersions.map {
  case ((dep: String, org: String), v: String) => {
    org %% dep % sys.props.getOrElse(dep + "Version", v)
  }
}.toSeq

// Compiler plugin (necessary from chisel 5.0)
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)

scalacOptions ++= scalacOptionsVersion(scalaVersion.value) ++ Seq(
  "-language:reflectiveCalls",
  "-deprecation",
  "-feature",
  "-Xcheckinit",
  "-Ymacro-annotations"
)
javacOptions ++= javacOptionsVersion(scalaVersion.value)

// The throughput speed test lives in its own configuration/source directory so
// it is not part of the default `test` run. Run it with:
//   sbt "Speed / test"
lazy val Speed = config("speed") extend Test
configs(Speed)
inConfig(Speed)(Defaults.testSettings)
Speed / scalaSource := baseDirectory.value / "src" / "speed" / "scala"

// The stress suite lives in its own configuration/source directory so it is not
// part of the default `test` run. Run it with:
//   sbt "Stress / test"
lazy val Stress = config("stress") extend Test
configs(Stress)
inConfig(Stress)(Defaults.testSettings)
Stress / scalaSource := baseDirectory.value / "src" / "stress" / "scala"

// `Defaults.testSettings` on the custom Speed/Stress configurations also defines
// `sourceDirectories`/`semanticdbTargetRoot`, which sbt's unused-key lint flags
// because we drive compilation through `scalaSource` instead. They are harmless
// (and read by `compile`), so exclude them from the lint rather than silence the
// whole check.
Global / excludeLintKeys ++= Set(
  Speed / sourceDirectories,
  Speed / semanticdbTargetRoot,
  Stress / sourceDirectories,
  Stress / semanticdbTargetRoot
)
