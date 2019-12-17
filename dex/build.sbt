import java.nio.charset.StandardCharsets

import DexDockerKeys._
import com.typesafe.sbt.SbtNativePackager.Universal
import CommonSettings.autoImport.network
import com.typesafe.sbt.packager.archetypes.TemplateWriter

enablePlugins(JavaServerAppPackaging, UniversalDeployPlugin, JDebPackaging, SystemdPlugin, DexDockerPlugin, RunApplicationSettings, GitVersioning)

resolvers += "dnvriend" at "https://dl.bintray.com/dnvriend/maven"
libraryDependencies ++= Dependencies.dex ++ Dependencies.silencer

val packageSettings = Seq(
  maintainer := "wavesplatform.com",
  packageSummary := "DEX",
  packageDescription := "Decentralized EXchange for Waves network"
)

packageSettings
inScope(Global)(packageSettings)

lazy val versionSourceTask = Def.task {
  val versionFile      = sourceManaged.value / "com" / "wavesplatform" / "dex" / "Version.scala"
  val versionExtractor = """(\d+)\.(\d+)\.(\d+).*""".r
  val (major, minor, patch) = version.value match {
    case versionExtractor(ma, mi, pa) => (ma.toInt, mi.toInt, pa.toInt)
    case x                            => throw new IllegalStateException(s"Can't parse version: $x")
  }

  IO.write(
    versionFile,
    s"""package com.wavesplatform.dex
       |
       |object Version {
       |  val VersionString = "${version.value}"
       |  val VersionTuple = ($major, $minor, $patch)
       |}
       |""".stripMargin,
    charset = StandardCharsets.UTF_8
  )
  Seq(versionFile)
}

inConfig(Compile)(
  Seq(
    sourceGenerators += versionSourceTask,
    discoveredMainClasses := Seq(
      "com.wavesplatform.dex.Application",
      "com.wavesplatform.dex.WavesDexCli"
    ),
    mainClass := discoveredMainClasses.value.headOption
  ))

// Docker

inTask(docker)(
  Seq(
    additionalFiles ++= Seq(
      (Universal / stage).value,
      (Compile / sourceDirectory).value / "container" / "start.sh",
      (Compile / sourceDirectory).value / "container" / "default.conf"
    )
  )
)

// Packaging

executableScriptName := "waves-dex"

// ZIP archive and mappings for all artifacts
inConfig(Universal)(
  Seq(
    packageName := s"waves-dex${network.value.packageSuffix}-${version.value}", // An archive file name
    mappings ++= {
      val docDir = packageSource.value / "doc"

      val baseDexConfigName  = s"${network.value}.conf"
      val localDexConfigFile = docDir / baseDexConfigName
      val dexConfigMapping = if (localDexConfigFile.exists()) {
        val artifactPath = "doc/main.conf"
        Some(localDexConfigFile -> artifactPath)
      } else None

      Seq(
        (docDir / "README.md") -> "doc/README.md",
        (docDir / "logback.xml") -> "doc/logback.xml",
      ) ++ dexConfigMapping.toSeq
    }
  ))

// DEB package
Linux / name := s"waves-dex${network.value.packageSuffix}" // A staging directory name
Linux / normalizedName := (Linux / name).value // An archive file name
Linux / packageName := (Linux / name).value    // In a control file

inConfig(Debian)(
  Seq(
    linuxStartScriptTemplate := (packageSource.value / "systemd.service").toURI.toURL,
    debianPackageDependencies += "java8-runtime-headless",
    serviceAutostart := false,
    maintainerScripts := maintainerScriptsFromDirectory(packageSource.value / "debian", Seq("preinst", "postinst", "postrm", "prerm")),
    linuxPackageMappings ++= {
      val upstartScript = {
        val src    = packageSource.value / "upstart.conf"
        val dest   = (target in Debian).value / "upstart" / s"${packageName.value}.conf"
        val result = TemplateWriter.generateScript(src.toURI.toURL, linuxScriptReplacements.value)
        IO.write(dest, result)
        dest
      }

      Seq(upstartScript -> s"/etc/init/${packageName.value}.conf").map(packageMapping(_).withConfig().withPerms("644"))
    },
    linuxScriptReplacements += "detect-loader" ->
      """is_systemd() {
        |    which systemctl >/dev/null 2>&1 && \
        |    systemctl | grep -- -\.mount >/dev/null 2>&1
        |}
        |is_upstart() {
        |    /sbin/init --version | grep upstart >/dev/null 2>&1
        |}
        |""".stripMargin
  ))
