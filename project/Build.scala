import com.typesafe.sbtscalariform.ScalariformPlugin._
import sbt._
import sbt.Keys._
import sbt.ScriptedPlugin._
import sbtrelease.ReleasePlugin._

object Build extends Build {

  lazy val root = Project(
    "nbsbt",
    file("."),
    aggregate = Seq(nbsbtCore, nbsbtPlugin),
    settings = commonSettings ++ Seq(
      publishArtifact := false
    )
  )

  lazy val nbsbtCore = Project(
    "nbsbt-core",
    file("nbsbt-core"),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Seq("org.scalaz" %% "scalaz-core" % "6.0.4")
    )
  )

  lazy val nbsbtPlugin = Project(
    "nbsbt-plugin",
    file("nbsbt-plugin"),
    dependencies = Seq(nbsbtCore),
    settings = commonSettings
  )

  def commonSettings = Defaults.defaultSettings ++
    scalariformSettings ++
    scriptedSettings ++
    releaseSettings ++
    Seq(
      scalaVersion := "2.10.3",
      organization := "org.netbeans.nbsbt",
      // version is defined in version.sbt in order to support sbt-release
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      publishTo <<= isSnapshot { isSnapshot =>
        val id = if (isSnapshot) "snapshots" else "releases"
        val uri = "https://typesafe.artifactoryonline.com/typesafe/ivy-" + id
        Some(Resolver.url("typesafe-" + id, url(uri))(Resolver.ivyStylePatterns))
      },
      sbtPlugin := true,
      publishMavenStyle := false,
      publishArtifact in (Compile, packageDoc) := false,
      publishArtifact in (Compile, packageSrc) := false,
      scriptedLaunchOpts += "-Xmx1024m"
    )
}
