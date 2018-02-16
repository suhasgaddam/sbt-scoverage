package scoverage

import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import scoverage.report.{CoverageAggregator, CoberturaXmlWriter, ScoverageHtmlWriter, ScoverageXmlWriter}

object ScoverageSbtPlugin extends AutoPlugin {

  val OrgScoverage = "org.scoverage"
  val ScalacRuntimeArtifact = "scalac-scoverage-runtime"
  val ScalacPluginArtifact = "scalac-scoverage-plugin"
  // this should match the version defined in build.sbt
  val DefaultScoverageVersion = "1.4.0-M2"
  val autoImport = ScoverageKeys
  lazy val ScoveragePluginConfig = config("scoveragePlugin").hide

  import autoImport._

  val aggregateFilter = ScopeFilter(inAggregates(ThisProject),
    inConfigurations(Compile)) // must be outside of the 'coverageAggregate' task (see: https://github.com/sbt/sbt/issues/1095 or https://github.com/sbt/sbt/issues/780)

  override def requires: JvmPlugin.type = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  override def globalSettings: Seq[Def.Setting[_]] = super.globalSettings ++ Seq(
    coverageEnabled := false,
    coverageExcludedPackages := "",
    coverageExcludedFiles := "",
    coverageMinimum := 0, // default is no minimum
    coverageFailOnMinimum := false,
    coverageBranchMinimum := 0, // default is no minimum
    coverageBranchFailOnMinimum := false,
    coverageHighlighting := true,
    coverageOutputXML := true,
    coverageOutputHTML := true,
    coverageOutputCobertura := true,
    coverageOutputDebug := false,
    coverageCleanSubprojectFiles := true,
    coverageOutputTeamCity := false,
    coverageScalacPluginVersion := DefaultScoverageVersion
  )

  override def buildSettings: Seq[Setting[_]] = super.buildSettings ++
    addCommandAlias("coverage", ";set coverageEnabled in ThisBuild := true") ++
    addCommandAlias("coverageOn", ";set coverageEnabled in ThisBuild := true") ++
    addCommandAlias("coverageOff", ";set coverageEnabled in ThisBuild := false")

  override def projectSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations += ScoveragePluginConfig,
    coverageReport := coverageReport0.value,
    coverageAggregate := coverageAggregate0.value,
    aggregate in coverageAggregate := false
  ) ++ coverageSettings ++ scalacSettings

  private lazy val coverageSettings = Seq(
    libraryDependencies  ++= {
      if (coverageEnabled.value)
        Seq(
          // We only add for "compile"" because of macros. This setting could be optimed to just "test" if the handling
          // of macro coverage was improved.
          OrgScoverage %% (scalacRuntime(libraryDependencies.value)) % coverageScalacPluginVersion.value,
          // We don't want to instrument the test code itself, nor add to a pom when published with coverage enabled.
          OrgScoverage %% ScalacPluginArtifact % coverageScalacPluginVersion.value % ScoveragePluginConfig.name
        )
      else
        Nil
    }
  )

  private lazy val scalacSettings = Seq(
    scalacOptions in(Compile, compile) ++= {
      val updateReport = update.value
      if (coverageEnabled.value) {
        val scoverageDeps: Seq[File] = updateReport matching configurationFilter(ScoveragePluginConfig.name)
        val pluginPath: File =  scoverageDeps.find(_.getAbsolutePath.contains(ScalacPluginArtifact)) match {
          case None => throw new Exception(s"Fatal: $ScalacPluginArtifact not in libraryDependencies")
          case Some(pluginPath) => pluginPath
        }
        Seq(
          Some(s"-Xplugin:${pluginPath.getAbsolutePath}"),
          Some(s"-P:scoverage:dataDir:${crossTarget.value.getAbsolutePath}/scoverage-data"),
          Option(coverageExcludedPackages.value.trim).filter(_.nonEmpty).map(v => s"-P:scoverage:excludedPackages:$v"),
          Option(coverageExcludedFiles.value.trim).filter(_.nonEmpty).map(v => s"-P:scoverage:excludedFiles:$v"),
          // rangepos is broken in some releases of scala so option to turn it off
          if (coverageHighlighting.value) Some("-Yrangepos") else None
        ).flatten
      } else {
        Nil
      }
    }
  )

  private def scalacRuntime(deps: Seq[ModuleID]): String = {
    ScalacRuntimeArtifact + optionalScalaJsSuffix(deps)
  }

  // returns "_sjs$sjsVersion" for Scala.js projects or "" otherwise
  private def optionalScalaJsSuffix(deps: Seq[ModuleID]): String = {
    val sjsClassifier = deps.collectFirst {
      case moduleId if moduleId.organization == "org.scala-js" && moduleId.name == "scalajs-library" => moduleId.revision
    }.map(_.take(3)).map(sjsVersion => "_sjs" + sjsVersion)

    sjsClassifier getOrElse ""
  }

  private lazy val coverageReport0 = Def.task {
    val target = crossTarget.value
    val log = streams.value.log

    log.info(s"Waiting for measurement data to sync...")
    Thread.sleep(1000) // have noticed some delay in writing on windows, hacky but works

    loadCoverage(target, log) match {
      case Some(cov) =>
        writeReports(
          target,
          (sourceDirectories in Compile).value,
          cov,
          coverageOutputCobertura.value,
          coverageOutputXML.value,
          coverageOutputHTML.value,
          coverageOutputDebug.value,
          coverageOutputTeamCity.value,
          sourceEncoding((scalacOptions in (Compile)).value),
          log)

        checkCoverage(cov, log, coverageMinimum.value, coverageFailOnMinimum.value)(
          Statement, cov.statementCoveragePercent, cov.statementCoverageFormatted)

        checkCoverage(cov, log, coverageBranchMinimum.value, coverageBranchFailOnMinimum.value)(
          Branch, cov.branchCoveragePercent, cov.branchCoverageFormatted)
      case None => log.warn("No coverage data, skipping reports")
    }
  }

  private lazy val coverageAggregate0 = Def.task {
    val log = streams.value.log
    log.info(s"Aggregating coverage from subprojects...")

    val xmlReportFiles = crossTarget.all(aggregateFilter).value map (_ / "scoverage-report" / Constants
      .XMLReportFilename) filter (_.isFile())
    CoverageAggregator.aggregate(xmlReportFiles, coverageCleanSubprojectFiles.value) match {
      case Some(cov) =>
        writeReports(
          crossTarget.value,
          sourceDirectories.all(aggregateFilter).value.flatten,
          cov,
          coverageOutputCobertura.value,
          coverageOutputXML.value,
          coverageOutputHTML.value,
          coverageOutputDebug.value,
          coverageOutputTeamCity.value,
          sourceEncoding((scalacOptions in (Compile)).value),
          log)
        val cfmt = cov.statementCoverageFormatted
        log.info(s"Aggregation complete. Coverage was [$cfmt]")

        checkCoverage(cov, log, coverageMinimum.value, coverageFailOnMinimum.value)(
          Statement, cov.statementCoveragePercent, cov.statementCoverageFormatted)

        checkCoverage(cov, log, coverageBranchMinimum.value, coverageBranchFailOnMinimum.value)(
          Branch, cov.branchCoveragePercent, cov.branchCoverageFormatted)
      case None =>
        log.info("No subproject data to aggregate, skipping reports")
    }
  }

  private def writeReports(crossTarget: File,
                           compileSourceDirectories: Seq[File],
                           coverage: Coverage,
                           coverageOutputCobertura: Boolean,
                           coverageOutputXML: Boolean,
                           coverageOutputHTML: Boolean,
                           coverageDebug: Boolean,
                           coverageOutputTeamCity: Boolean,
                           coverageSourceEncoding: Option[String],
                           log: Logger): Unit = {
    log.info(s"Generating scoverage reports...")

    val coberturaDir = crossTarget / "coverage-report"
    val reportDir = crossTarget / "scoverage-report"
    coberturaDir.mkdirs()
    reportDir.mkdirs()

    if (coverageOutputCobertura) {
      new CoberturaXmlWriter(compileSourceDirectories, coberturaDir).write(coverage)
      log.info(s"Written Cobertura report [${coberturaDir.getAbsolutePath}/cobertura.xml]")
    }

    if (coverageOutputXML) {
      new ScoverageXmlWriter(compileSourceDirectories, reportDir, false).write(coverage)
      if (coverageDebug) {
        new ScoverageXmlWriter(compileSourceDirectories, reportDir, true).write(coverage)
      }
      log.info(s"Written XML coverage report [${reportDir.getAbsolutePath}/scoverage.xml]")
    }

    if (coverageOutputHTML) {
      new ScoverageHtmlWriter(compileSourceDirectories, reportDir, coverageSourceEncoding).write(coverage)
      log.info(s"Written HTML coverage report [${reportDir.getAbsolutePath}/index.html]")
    }
    if (coverageOutputTeamCity) {
      reportToTeamcity(coverage, coverageOutputHTML, reportDir, crossTarget, log)
      log.info("Written coverage report to TeamCity")
    }

    log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
    log.info(s"Branch coverage....: ${coverage.branchCoverageFormatted}%")
    log.info("Coverage reports completed")
  }

  private def reportToTeamcity(coverage: Coverage,
                               createCoverageZip: Boolean,
                               reportDir: File,
                               crossTarget: File,
                               log: Logger) {

    def statsKeyValue(key: String, value: Int): String = s"##teamcity[buildStatisticValue key='$key' value='$value']"

    // Log statement coverage as per: https://devnet.jetbrains.com/message/5467985
    log.info(statsKeyValue("CodeCoverageAbsSCovered", coverage.invokedStatementCount))
    log.info(statsKeyValue("CodeCoverageAbsSTotal", coverage.statementCount))

    // Log branch coverage as a custom metrics (in percent)
    log.info(statsKeyValue("CodeCoverageBranch", "%.0f".format(coverage.branchCoveragePercent).toInt))

    // Create the coverage report for teamcity (HTML files)
    if (createCoverageZip)
      IO.zip(Path.allSubpaths(reportDir), crossTarget / "coverage.zip")
  }

  private def loadCoverage(crossTarget: File, log: Logger): Option[Coverage] = {

    val dataDir = crossTarget / "/scoverage-data"
    val coverageFile = Serializer.coverageFile(dataDir)

    log.info(s"Reading scoverage instrumentation [$coverageFile]")

    if (coverageFile.exists) {

      val coverage = Serializer.deserialize(coverageFile)

      log.info(s"Reading scoverage measurements...")
      val measurementFiles = IOUtils.findMeasurementFiles(dataDir)
      val measurements = IOUtils.invoked(measurementFiles)
      coverage.apply(measurements)
      Some(coverage)

    } else {
      None
    }
  }

  sealed trait CoverageType
  private final case object Statement extends CoverageType
  private final case object Branch extends CoverageType

  private def checkCoverage(coverage: Coverage,
                            log: Logger,
                            min: Double,
                            failOnMin: Boolean)(
                            coverageType: CoverageType,
                            percentage: Double,
                            formatted: String): Unit = {

    // check for default minimum
    if (min > 0) {
      def is100(d: Double) = Math.abs(100 - d) <= 0.00001

      if (is100(min) && is100(percentage)) {
        log.info(s"100% $coverageType Coverage !")
      } else if (min > percentage) {
        log.error(s"$coverageType Coverage is below minimum [$formatted% < $min%]")
        if (failOnMin)
          throw new RuntimeException(s"$coverageType Coverage minimum was not reached")
      } else {
        log.info(s"$coverageType Coverage is above minimum [$formatted% > $min%]")
      }
    }

    log.info(s"All done. $coverageType Coverage was [$formatted%]")
  }

  private def sourceEncoding(scalacOptions: Seq[String]): Option[String] = {
    val i = scalacOptions.indexOf("-encoding") + 1
    if (i > 0 && i < scalacOptions.length) Some(scalacOptions(i)) else None
  }

}
