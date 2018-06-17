import $ivy.`com.typesafe::pom-model:2.2.0`
import $ivy.`net.sourceforge.fmpp:fmpp:0.9.15`
import com.typesafe.pom._, MavenHelper._, MavenUserSettingsHelper._
import org.apache.maven.model.{Dependency => PomDependency, _}
import org.apache.maven.settings.{Settings ⇒ MavenSettings}
import coursier.maven.MavenRepository

import ammonite.ops._
import mill._
import mill.util.Ctx
import mill.modules.Jvm
import mill.scalalib._
import mill.scalalib.scalafmt._
import scala.collection.JavaConverters._

trait SchedoscopeModule extends PomModule {
  def scalaVersion = "2.11.12"
}

object `schedoscope-conf` extends SchedoscopeModule {
}

object `schedoscope-export` extends SchedoscopeModule

object `schedoscope-core` extends SchedoscopeModule with FmppModule with JavaccModule {
  def moduleDeps = Seq(`schedoscope-conf`, `schedoscope-export`)
  def javaccFiles = T { expandFmpp().map(_.path).filter(p => p.isFile && p.ext == "jj") }
  override def generatedSources = T { generateParser() }
}

trait JavaccModule extends JavaModule {
  def javaccSourceDirectories: T[Seq[PathRef]] = T { resources() }
  def javaccFiles: T[Seq[Path]] = T { javaccSourceDirectories().flatMap(ref => ls.rec(ref.path)).filter(p => p.isFile && p.ext == "jj") }
  def javaccVersion: T[String] = "7.0.3"
  def javaccDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"net.java.dev.javacc:javacc:${javaccVersion()}")
    )
  }
  def doExpand(files: Seq[Path], outputPath: Path, classpath: Agg[Path])(implicit ctx: Ctx): Seq[PathRef] = {
    files.map { file =>
      val args = s"-OUTPUT_DIRECTORY=${outputPath} $file".split(" ")
      Jvm.subprocess(
        "org.javacc.parser.Main",
        classpath,
        mainArgs = args)(ctx)
    }
    ls.rec(outputPath).map(PathRef(_))
  }
  def generateParser: T[Seq[PathRef]] = T {
    doExpand(javaccFiles(), T.ctx().dest, javaccDeps().map(_.path))
  }
}

trait FmppModule extends ScalaModule {
  def fmppArgs: T[Seq[String]] = Seq("--ignore-temporary-files")
  def fmppMain: String = "fmpp.tools.CommandLine"
  def fmppSources: T[Seq[String]] = Seq("scala", "java")
  def fmppVersion: T[String] = "0.9.14"

  def fmppDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      scalaWorker.repositories,
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"net.sourceforge.fmpp:fmpp:${fmppVersion()}")
    )
  }
  def doExpand(sourceTypeLocations: Map[String, Path], outputPath: Path, classpath: Agg[Path])(implicit ctx: Ctx): Seq[PathRef] = {
    sourceTypeLocations.foreach { case(ext, input) =>
      val args = List(
        "-S", input.toString, "-O", outputPath.toString,
        "-C", input.toString + "/fmpp"
        //"--replace-extensions=fmpp, " + ext,
        //"-M", "execute(**/*.fmpp), ignore(**/*)"
      )
      Jvm.subprocess(
        fmppMain,
        classpath,
        mainArgs = args)(ctx)
    }
    ls.rec(outputPath).map(PathRef(_))
  }
  def expandFmpp: T[Seq[PathRef]] = T {
    val sourceTypeLocations: Map[String, Path] = fmppSources().map(s => s -> resources().head.path).toMap
    doExpand(sourceTypeLocations, T.ctx().dest, fmppDeps().map(_.path))
  }
}
trait PomModule extends SbtModule {
  def pomFile = millSourcePath / "pom.xml"
  def localRepo = defaultLocalRepo
  def profiles: Seq[String] = Seq.empty
  def mavenUserProperties: Map[String, String] = Map.empty
  def settingsLocation = ammonite.ops.home / "m2settings.xml"
  def effectivePom = loadEffectivePom(pomFile.toIO, localRepo, profiles, mavenUserProperties)
  lazy val effectiveSettings = loadUserSettings(settingsLocation.toIO, profiles)
  def scalaVersion = getScalaVersion(effectivePom).get//OrElse("2.12.6")
  def name: String = removeBinaryVersionSuffix(effectivePom.getArtifactId)
  def organization: String = effectivePom.getGroupId
  def version: String = effectivePom.getVersion
  def moduleDepsCoordinates = moduleDeps.collect {
    case p: PomModule => (p.organization, p.name, p.version)
  }
  def convertDep(dep: PomDependency) = {
    // TODO - Handle mapping all the maven oddities into sbt's DSL.
    val scopeString: Option[String] = Option(dep.getScope)

    //def fixScope(m: ModuleID): ModuleID =
    //  scopeString match {
    //    case Some(scope) => m % scope
    //    case None => m
    //  }

    //def addExclusions(mod: ModuleID): ModuleID = {
    //  val exclusions = dep.getExclusions.asScala
    //  exclusions.foldLeft(mod) { (mod, exclude) =>
    //    mod.exclude(exclude.getGroupId, exclude.getArtifactId)
    //  }
    //}
    //def addClassifier(mod: ModuleID): ModuleID = {
    //  Option(dep.getClassifier) match {
    //    case Some(_classifier) => mod classifier _classifier
    //    case None => mod
    //  }
    //}
    //addExclusions(addClassifier(fixScope(dep.getGroupId % dep.getArtifactId % dep.getVersion)))
    import dep._
    ivy"$getGroupId:$getArtifactId:$getVersion"
  }
  def ivyDeps = Agg(
    effectivePom
      .getDependencies
      .asScala
      .filterNot(dep => moduleDepsCoordinates.contains((dep.getGroupId, dep.getArtifactId, dep.getVersion)))
      .map(convertDep): _*
  )

  def repositories() =
    super.repositories ++
      pomRepositories ++
      effectiveSettings.toSeq.flatMap(userRepositories)

  def pomRepositories: Seq[MavenRepository] =
    for {
      repo <- effectivePom.getRepositories.asScala
      // TODO - Support other layouts
      if repo.getLayout == "default"
    } yield MavenRepository(repo.getUrl)

  /** Extract any resolvers defined through the user settings.xml file. */
  def userRepositories(settings: MavenSettings): Seq[MavenRepository] = {
    val profiles = settings.getProfilesAsMap
    for {
      profileName ← settings.getActiveProfiles.asScala
      profile ← Option(profiles.get(profileName)).toSeq
      repo ← profile.getRepositories.asScala
    } yield MavenRepository(repo.getUrl)

  }
}
