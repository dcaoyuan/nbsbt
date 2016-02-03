resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"

libraryDependencies <+= (sbtVersion)("org.scala-sbt" % "scripted-plugin" % _)

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.0")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")
