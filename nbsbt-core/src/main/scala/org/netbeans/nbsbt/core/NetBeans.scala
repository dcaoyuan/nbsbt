/*
 * Copyright 2011 Typesafe Inc.
 *
 * This work is based on the original contribution of WeigleWilczek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.netbeans.nbsbt.core

import NetBeansPlugin.{
  NetBeansClasspathEntry,
  NetBeansTransformerFactory,
  NetBeansClasspathEntryTransformerFactory,
  NetBeansRewriteRuleTransformerFactory,
  NetBeansCreateSrc,
  NetBeansProjectFlavor,
  NetBeansExecutionEnvironment,
  NetBeansKeys
}
import java.io.{FileWriter, Writer}
import java.util.Properties
import sbt.{
  Attributed,
  Artifact,
  ClasspathDep,
  Classpaths,
  Command,
  Configuration,
  Configurations,
  File,
  IO,
  Keys,
  ModuleID,
  Project,
  ProjectRef,
  Reference,
  ResolvedProject,
  SettingKey,
  State,
  TaskKey,
  ThisBuild,
  UpdateReport,
  richFile
}
import sbt.complete.Parser
import scala.xml.{Node, PrettyPrinter}
import scala.xml.transform.{RewriteRule, RuleTransformer}
import scalaz.{Failure, NonEmptyList, Success}
import scalariform.formatter.preferences.PreferenceDescriptor
import scalaz.Scalaz._
import scalaz.effect._
import scalaz.std.tuple._
import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences.FormattingPreferences

private object NetBeans extends NetBeansSDTConfig {
  val SettingFormat = """-([^:]*):?(.*)""".r

  val FileSepPattern = FileSep.replaceAll("""\\""", """\\\\""")

  val JreContainer = "org.eclipse.jdt.launching.JRE_CONTAINER"

  val StandardVmType = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType"

  val ScalaBuilder = "org.scala-ide.sdt.core.scalabuilder"

  val ScalaNature = "org.scala-ide.sdt.core.scalanature"

  val JavaBuilder = "org.eclipse.jdt.core.javabuilder"

  val JavaNature = "org.eclipse.jdt.core.javanature"

  case class ScopedConfiguration[A](configuration: Configuration, values: Seq[A])

  def netbeansCommand(commandName: String): Command =
    Command(commandName)(_ => parser)((state, args) => action(args.toMap, state))

  def parser: Parser[Seq[(String, Any)]] = {
    import NetBeansOpts._
    (executionEnvironmentOpt | boolOpt(SkipParents) | boolOpt(WithSource) | boolOpt(GenNetBeans)).*
  }

  def executionEnvironmentOpt: Parser[(String, NetBeansExecutionEnvironment.Value)] = {
    import NetBeansExecutionEnvironment._
    import NetBeansOpts._
    import sbt.complete.DefaultParsers._
    val (head :: tail) = valueSeq map (_.toString)
    val executionEnvironments = tail.foldLeft(head: Parser[String])(_ | _)
    (Space ~> ExecutionEnvironment ~ ("=" ~> executionEnvironments)) map { case (k, v) => k -> withName(v) }
  }

  def action(args: Map[String, Any], state: State): State = {
    state.log.info("About to create NetBeans project files for your project(s).")
    import NetBeansOpts._
    handleProjects(
      (args get ExecutionEnvironment).asInstanceOf[Option[NetBeansExecutionEnvironment.Value]],
      (args get SkipParents).asInstanceOf[Option[Boolean]] getOrElse skipParents(ThisBuild, state),
      (args get WithSource).asInstanceOf[Option[Boolean]],
      (args get GenNetBeans).asInstanceOf[Option[Boolean]] getOrElse true,
      state
    ).fold(onFailure(state), onSuccess(state))
  }

  def handleProjects(
    executionEnvironmentArg: Option[NetBeansExecutionEnvironment.Value],
    skipParents:             Boolean,
    withSourceArg:           Option[Boolean],
    genNetBeans:             Boolean,
    state:                   State
  ): Validation[IO[Seq[String]]] = {
    val effects = for {
      ref <- structure(state).allProjectRefs
      project <- Project.getProject(ref, structure(state)) if !skip(ref, project, skipParents, state)
    } yield {
      val configs = configurations(ref, state)

      for {
        classpathEntryTransformer <- classpathEntryTransformerFactory(ref, state).createTransformer(ref, state)
        classpathTransformers <- (classpathTransformerFactories(ref, state).toList map (_.createTransformer(ref, state))).sequence[Validation, RewriteRule]
        projectTransformers <- (projectTransformerFactories(ref, state).toList map (_.createTransformer(ref, state))).sequence[Validation, RewriteRule]
        name <- name(ref, state)
        projectId <- projectId(ref, state)
        buildDirectory <- buildDirectory(state)
        baseDirectory <- baseDirectory(ref, state)
        srcDirectories <- mapConfigurations(configs, config => srcDirectories(ref, createSrc(ref, state)(config), netbeansOutput(ref, state)(config), state)(config))
        scalacOptions <- scalacOptions(ref, state)
        scalariformPreferences <- mapConfigurations(configs, scalariformPreferences(ref, state))
        externalDependencies <- mapConfigurations(configs, externalDependencies(ref, withSourceArg getOrElse withSource(ref, state), state))
        projectDependencies <- mapConfigurations(configs, projectDependencies(ref, project, state))
        projectAggregate <- projectAggregate(ref, project, state)
      } yield {
        handleProject(
          jreContainer(executionEnvironmentArg orElse executionEnvironment(ref, state)),
          preTasks(ref, state),
          relativizeLibs(ref, state),
          builderAndNatures(projectFlavor(ref, state)),
          genNetBeans,
          state
        )(
            classpathEntryTransformer,
            classpathTransformers,
            projectTransformers,
            name,
            projectId,
            buildDirectory,
            baseDirectory,
            srcDirectories,
            scalacOptions,
            scalariformPreferences,
            externalDependencies,
            projectDependencies,
            projectAggregate
          )
      }
    }
    effects.toList.sequence[Validation, IO[String]].map((list: List[IO[String]]) => list.toStream.sequence.map(_.toList))
  }

  /**
   * This method will remove 'compile' scope. TODO: what should it do?
   */
  def removeExtendedConfigurations(configurations: Seq[Configuration]): Seq[Configuration] = {
    def findExtended(configurations: Seq[Configuration], acc: Seq[Configuration] = Nil): Seq[Configuration] = {
      val extended = configurations flatMap (_.extendsConfigs)
      if (extended.isEmpty)
        acc
      else
        findExtended(extended, extended ++ acc)
    }
    configurations filterNot findExtended(configurations).contains
  }

  def onFailure(state: State)(errors: NonEmptyList[String]): State = {
    state.log.error(
      "Could not create NetBeans project files:%s%s".format(NewLine, errors.list mkString NewLine)
    )
    state
  }

  def onSuccess(state: State)(effects: IO[Seq[String]]): State = {
    val names = effects.unsafePerformIO
    if (names.isEmpty)
      state.log.warn("There was no project to create NetBeans project files for!")
    else
      state.log.info(
        "Successfully created NetBeans project files for project(s):%s%s".format(
          NewLine,
          names mkString NewLine
        )
      )
    state
  }

  def skip(ref: ProjectRef, project: ResolvedProject, skipParents: Boolean, state: State): Boolean =
    skip(ref, state) || (skipParents && !project.aggregate.isEmpty)

  def mapConfigurations[A](
    configurations: Seq[Configuration],
    f:              Configuration => Validation[Seq[A]]
  ): Validation[List[(Configuration, Seq[A])]] = {
    def scoped(c: Configuration): Validation[(Configuration, Seq[A])] = f(c) fold (e => Failure(e), s => Success((c, s.distinct)))
    (configurations map scoped).toList.sequence
  }

  def handleProject(
    jreContainer:      String,
    preTasks:          Seq[(TaskKey[_], ProjectRef)],
    relativizeLibs:    Boolean,
    builderAndNatures: (String, Seq[String]),
    genNetBeans:       Boolean,
    state:             State
  )(
    classpathEntryTransformer: Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry],
    classpathTransformers:     Seq[RewriteRule],
    projectTransformers:       Seq[RewriteRule],
    name:                      String,
    projectId:                 String,
    buildDirectory:            File,
    baseDirectory:             File,
    srcDirectories:            Seq[(Configuration, Seq[(File, File, Boolean)])],
    scalacOptions:             Seq[(String, String)],
    scalariformPreferences:    Seq[(Configuration, Seq[(PreferenceDescriptor[_], Any)])],
    externalDependencies:      Seq[(Configuration, Seq[Lib])],
    projectDependencies:       Seq[(Configuration, Seq[Prj])],
    projectAggregate:          Seq[Prj]
  ): IO[String] = {
    for {
      _ <- executePreTasks(preTasks, state)
      n <- io(name)
      srcDirs <- splitSrcDirectories(srcDirectories, baseDirectory)
      _ <- if (genNetBeans) io(()) else saveXml(baseDirectory / ".project", new RuleTransformer(projectTransformers: _*)(projectXml(name, builderAndNatures)))
      cp <- classpath(
        classpathEntryTransformer,
        name,
        projectId,
        buildDirectory,
        baseDirectory,
        relativizeLibs,
        srcDirs,
        externalDependencies,
        projectDependencies,
        projectAggregate,
        jreContainer,
        scalariformPreferences,
        genNetBeans,
        state
      )
      _ <- if (genNetBeans) saveXml(baseDirectory / ".classpath_nb", cp) else saveXml(baseDirectory / ".classpath", new RuleTransformer(classpathTransformers: _*)(cp))
      _ <- if (genNetBeans) io(()) else saveProperties(baseDirectory / ".settings" / "org.scala-ide.sdt.core.prefs", scalacOptions)
    } yield n
  }

  def executePreTasks(preTasks: Seq[(TaskKey[_], ProjectRef)], state: State): IO[Unit] =
    io(for ((preTask, ref) <- preTasks) evaluateTask(preTask, ref, state))

  def projectXml(name: String, builderAndNatures: (String, Seq[String])): Node =
    <projectDescription>
      <name>{ name }</name>
      <buildSpec>
        <buildCommand>
          <name>{ builderAndNatures._1 }</name>
        </buildCommand>
      </buildSpec>
      <natures>
        { builderAndNatures._2.map(n => <nature>{ n }</nature>) }
      </natures>
    </projectDescription>

  def createLinkName(file: File, baseDirectory: File): String = {
    val name = file.getCanonicalPath
    // just put '-' in place of bad characters for the name... (for now).
    // in the future we should limit the size via relativizing magikz.
    name.replaceAll("[\\s\\\\/]+", "-")
  }

  def splitSrcDirectories(conf: Configuration, srcDirectories: Seq[(File, File, Boolean)], baseDirectory: File): (Configuration, Seq[(File, File, Boolean)], Seq[(File, String, File, Boolean)]) = {
    val (local, linked) =
      srcDirectories partition {
        case (dir, _, _) => relativizeOpt(baseDirectory, dir).isDefined
      }
    //Now, create link names...

    val links =
      for {
        (file, classDirectory, managed) <- linked
        name = createLinkName(file, baseDirectory)
      } yield (file, name, classDirectory, managed)

    (conf, local, links)
  }

  def splitSrcDirectories(srcDirectories: Seq[(Configuration, Seq[(File, File, Boolean)])], baseDirectory: File): IO[Seq[(Configuration, Seq[(File, File, Boolean)], Seq[(File, String, File, Boolean)])]] = io {
    srcDirectories map { case (conf, srcDirs) => splitSrcDirectories(conf, srcDirs, baseDirectory) }
  }

  def classpath(
    classpathEntryTransformer: Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry],
    name:                      String,
    projectId:                 String,
    buildDirectory:            File,
    baseDirectory:             File,
    relativizeLibs:            Boolean,
    srcDirectories:            Seq[(Configuration, Seq[(File, File, Boolean)], Seq[(File, String, File, Boolean)])],
    externalDependencies:      Seq[(Configuration, Seq[Lib])],
    projectDependencies:       Seq[(Configuration, Seq[Prj])],
    projectAggregate:          Seq[Prj],
    jreContainer:              String,
    scalariformPreferences:    Seq[(Configuration, Seq[(PreferenceDescriptor[_], Any)])],
    genNetBeans:               Boolean,
    state:                     State
  ): IO[Node] = {
    val srcEntriesIoSeq =
      for ((config, dirs, links) <- srcDirectories; (dir, output, managed) <- dirs) yield srcEntry(config, baseDirectory, dir, output, managed, genNetBeans, state)
    val srcLinkEntriesIoSeq =
      for ((config, dirs, links) <- srcDirectories; (dir, name, output, managed) <- links) yield srcLink(config, baseDirectory, dir, name, output, managed, genNetBeans, state)
    for (
      srcEntries <- srcEntriesIoSeq.toList.sequence;
      linkEntries <- srcLinkEntriesIoSeq.toList.sequence
    ) yield {
      val entries = srcEntries ++ linkEntries ++
        (externalDependencies map { case (config, libs) => libs map libEntry(config, buildDirectory, baseDirectory, relativizeLibs, state) }).flatten ++
        (projectDependencies map { case (config, prjs) => prjs map projectEntry(config, baseDirectory, state) }).flatten ++
        (if (genNetBeans) (projectAggregate map aggProjectEntry(baseDirectory, state)) else Seq()) ++
        (Seq(jreContainer) map NetBeansClasspathEntry.Con) ++
        (Seq("bin") map NetBeansClasspathEntry.Output) ++
        (scalariformPreferences map { case (config, prefs) => prefs map { case (k, v) => NetBeansClasspathEntry.ScalariformEntry(config.name, k.key, v) } }).flatten
      if (genNetBeans) <classpath name={ name } id={ projectId }>{ classpathEntryTransformer(entries) map (_.toXmlNetBeans) }</classpath>
      else <classpath>{ classpathEntryTransformer(entries) map (_.toXml) }</classpath>
    }
  }

  def srcLink(
    config:         Configuration,
    baseDirectory:  File,
    linkedDir:      File,
    linkName:       String,
    classDirectory: File,
    managed:        Boolean,
    genNetBeans:    Boolean,
    state:          State
  ): IO[NetBeansClasspathEntry.Link] =
    io {
      if (!linkedDir.exists && !genNetBeans) linkedDir.mkdirs()
      NetBeansClasspathEntry.Link(
        config.name,
        linkName,
        relativize(baseDirectory, classDirectory),
        managed
      )
    }

  def srcEntry(
    config:         Configuration,
    baseDirectory:  File,
    srcDirectory:   File,
    classDirectory: File,
    managed:        Boolean,
    genNetBeans:    Boolean,
    state:          State
  ): IO[NetBeansClasspathEntry.Src] =
    io {
      if (!srcDirectory.exists() && !genNetBeans) srcDirectory.mkdirs()
      NetBeansClasspathEntry.Src(
        config.name,
        relativize(baseDirectory, srcDirectory),
        relativize(baseDirectory, classDirectory),
        managed
      )
    }

  def libEntry(
    config:         Configuration,
    buildDirectory: File,
    baseDirectory:  File,
    relativizeLibs: Boolean,
    state:          State
  )(
    lib: Lib
  ): NetBeansClasspathEntry.Lib = {
    def path(file: File) = {
      val relativizedBase =
        if (buildDirectory === baseDirectory) Some(".") else IO.relativize(buildDirectory, baseDirectory)
      val relativizedFile = IO.relativize(buildDirectory, file)
      val relativized = (relativizedBase |@| relativizedFile)((base, file) =>
        "%s%s%s".format(
          base split FileSepPattern map (part => if (part != ".") ".." else part) mkString FileSep,
          FileSep,
          file
        ))
      if (relativizeLibs) relativized getOrElse file.getAbsolutePath else file.getAbsolutePath
    }
    NetBeansClasspathEntry.Lib(config.name, path(lib.binary), lib.source map path)
  }

  def projectEntry(
    config:        Configuration,
    baseDirectory: File,
    state:         State
  )(
    prj: Prj
  ): NetBeansClasspathEntry.Project = {
    NetBeansClasspathEntry.Project(
      config.name,
      prj.name,
      prj.baseDirectory.getAbsolutePath,
      prj.classDirectory map (_.getAbsolutePath) getOrElse ""
    )
  }

  def aggProjectEntry(
    baseDirectory: File,
    state:         State
  )(
    prj: Prj
  ): NetBeansClasspathEntry.AggProject = {
    NetBeansClasspathEntry.AggProject(
      prj.name,
      prj.baseDirectory.getAbsolutePath
    )
  }

  def jreContainer(executionEnvironment: Option[NetBeansExecutionEnvironment.Value]): String =
    executionEnvironment match {
      case Some(ee) => "%s/%s/%s".format(JreContainer, StandardVmType, ee)
      case None     => JreContainer
    }

  def builderAndNatures(projectFlavor: NetBeansProjectFlavor.Value) =
    if (projectFlavor == NetBeansProjectFlavor.Scala)
      ScalaBuilder -> Seq(ScalaNature, JavaNature)
    else
      JavaBuilder -> Seq(JavaNature)

  // Getting and transforming mandatory settings and task results

  def name(ref: Reference, state: State): Validation[String] =
    setting(Keys.name in ref, state)

  def projectId(ref: Reference, state: State): Validation[String] =
    setting(Keys.thisProject in ref, state) map (_.id)

  def buildDirectory(state: State): Validation[File] =
    setting(Keys.baseDirectory in ThisBuild, state)

  def baseDirectory(ref: Reference, state: State): Validation[File] =
    setting(Keys.baseDirectory in ref, state)

  def target(ref: Reference, state: State): Validation[File] =
    setting(Keys.target in ref, state)

  def srcDirectories(
    ref:            Reference,
    createSrc:      NetBeansCreateSrc.ValueSet,
    netbeansOutput: Option[String],
    state:          State
  )(
    configuration: Configuration
  ): Validation[Seq[(File, File, Boolean)]] = {
    import NetBeansCreateSrc._
    val classDirectory = netbeansOutput match {
      case Some(name) => baseDirectory(ref, state) map (new File(_, name))
      case None       => setting(Keys.classDirectory in (ref, configuration), state)
    }
    def dirs(values: ValueSet, key: SettingKey[Seq[File]], managed: Boolean): Validation[List[(File, java.io.File, Boolean)]] =
      if (values subsetOf createSrc)
        (setting(key in (ref, configuration), state) <**> classDirectory)((sds, cd) => sds.toList map (sd => (sd, cd, managed)))
      else
        Success(List.empty)
    List(
      dirs(ValueSet(Unmanaged, Source), Keys.unmanagedSourceDirectories, false),
      dirs(ValueSet(Managed, Source), Keys.managedSourceDirectories, true),
      dirs(ValueSet(Unmanaged, Resource), Keys.unmanagedResourceDirectories, false),
      dirs(ValueSet(Managed, Resource), Keys.managedResourceDirectories, true)
    ) reduceLeft (_ +++ _)
  }

  def scalacOptions(ref: ProjectRef, state: State): Validation[Seq[(String, String)]] =
    evaluateTask(Keys.scalacOptions, ref, state) map (options =>
      if (options.isEmpty)
        Nil
      else {
        fromScalacToSDT(options) match {
          case Seq()   => Seq()
          case options => ("scala.compiler.useProjectSettings" -> "true") +: options
        }
      })

  /**
   * sbt command:
   *   > show compile:scalariformPreferences
   */
  def scalariformPreferences(ref: ProjectRef, state: State)(
    configuration: Configuration
  ): Validation[Seq[(PreferenceDescriptor[_], Any)]] = {
    (ScalariformKeys.preferences in (ref, configuration)) get structure(state).data match {
      case Some(a) => a.preferencesMap.toList.success
      case None    => FormattingPreferences.preferencesMap.toList.success
    }
  }

  def externalDependencies(
    ref:        ProjectRef,
    withSource: Boolean,
    state:      State
  )(
    configuration: Configuration
  ): Validation[Seq[Lib]] = {
    def moduleToFile(key: TaskKey[UpdateReport], p: (Artifact, File) => Boolean = (_, _) => true) =
      evaluateTask(key in configuration, ref, state) map { updateReport =>
        val moduleToFile =
          for {
            configurationReport <- (updateReport configuration configuration.name).toSeq
            moduleReport <- configurationReport.modules
            (artifact, file) <- moduleReport.artifacts if p(artifact, file)
          } yield moduleReport.module -> file
        moduleToFile.toMap
      }
    def libs(files: Seq[Attributed[File]], binaries: Map[ModuleID, File], sources: Map[ModuleID, File]) = {
      val binaryFilesToSourceFiles =
        for {
          (moduleId, binaryFile) <- binaries
          sourceFile <- sources get moduleId
        } yield binaryFile -> sourceFile
      files.files map (file => Lib(file)(binaryFilesToSourceFiles get file))
    }
    val externalDependencyClasspath =
      evaluateTask(Keys.externalDependencyClasspath in configuration, ref, state)
    val binaryModuleToFile = moduleToFile(Keys.update)
    val sourceModuleToFile =
      if (withSource)
        moduleToFile(Keys.updateClassifiers, (artifact, _) => artifact.classifier === Some("sources"))
      else
        Map[ModuleID, File]().success
    val externalDependencies =
      (externalDependencyClasspath |@| binaryModuleToFile |@| sourceModuleToFile)(libs)
    state.log.debug(
      "External dependencies for configuration '%s' and withSource '%s': %s".format(
        configuration,
        withSource,
        externalDependencies
      )
    )
    externalDependencies
  }

  def projectDependencies(
    ref:     ProjectRef,
    project: ResolvedProject,
    state:   State
  )(
    configuration: Configuration
  ): Validation[Seq[Prj]] = {
    val projectDependencies: Seq[Validation[Prj]] = project.dependencies collect {
      case dependency if isInConfiguration(configuration, ref, dependency, state) =>
        val dependencyRef = dependency.project
        val name = setting(Keys.name in dependencyRef, state)
        val baseDir = setting(Keys.baseDirectory in dependencyRef, state)
        val classDir = setting(Keys.classDirectory in (dependencyRef, Configurations.Compile), state) fold (f => Failure(f), s => Success(Option(s)))
        (name |@| baseDir |@| classDir)(Prj)
    }
    val projectDependenciesSeq = projectDependencies.toList.sequence
    state.log.debug("Project dependencies for configuration '%s': %s".format(configuration, projectDependenciesSeq))
    projectDependenciesSeq
  }

  def projectAggregate(
    ref:     ProjectRef,
    project: ResolvedProject,
    state:   State
  ): Validation[Seq[Prj]] = {
    val projects: Seq[Validation[Prj]] = project.aggregate collect {
      case prjRef if isUnderLocal(prjRef, state) =>
        val name = setting(Keys.name in prjRef, state)
        val baseDir = setting(Keys.baseDirectory in prjRef, state)
        (name |@| baseDir |@| Success(None))(Prj)
    }
    val projectsSeq = projects.toList.sequence
    state.log.debug("Project aggregate: %s".format(projectsSeq))
    projectsSeq
  }

  def isUnderLocal(prjRef: ProjectRef, state: State): Boolean = {
    val tryName = setting(Keys.name in prjRef, state)
    tryName.isSuccess
  }

  def isInConfiguration(
    configuration: Configuration,
    ref:           ProjectRef,
    dependency:    ClasspathDep[ProjectRef],
    state:         State
  ): Boolean = {
    val map = Classpaths.mapped(
      dependency.configuration,
      Configurations.names(Classpaths.getConfigurations(ref, structure(state).data)),
      Configurations.names(Classpaths.getConfigurations(dependency.project, structure(state).data)),
      "compile", "*->compile"
    )
    !map(configuration.name).isEmpty
  }

  // Getting and transforming optional settings and task results

  def executionEnvironment(ref: Reference, state: State): Option[NetBeansExecutionEnvironment.Value] =
    setting(NetBeansKeys.executionEnvironment in ref, state).fold(_ => None, id)

  def skipParents(ref: Reference, state: State): Boolean =
    setting(NetBeansKeys.skipParents in ref, state).fold(_ => false, id)

  def withSource(ref: Reference, state: State): Boolean =
    setting(NetBeansKeys.withSource in ref, state).fold(_ => false, id)

  def classpathEntryTransformerFactory(ref: Reference, state: State): NetBeansTransformerFactory[Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry]] =
    setting(NetBeansKeys.classpathEntryTransformerFactory in ref, state).fold(
      _ => NetBeansClasspathEntryTransformerFactory.Identity,
      id
    )

  def classpathTransformerFactories(ref: Reference, state: State): Seq[NetBeansTransformerFactory[RewriteRule]] =
    setting(NetBeansKeys.classpathTransformerFactories in ref, state).fold(
      _ => Seq(NetBeansRewriteRuleTransformerFactory.ClasspathDefault),
      NetBeansRewriteRuleTransformerFactory.ClasspathDefault +: _
    )

  def projectTransformerFactories(ref: Reference, state: State): Seq[NetBeansTransformerFactory[RewriteRule]] =
    setting(NetBeansKeys.projectTransformerFactories in ref, state).fold(
      _ => Seq(NetBeansRewriteRuleTransformerFactory.Identity),
      id
    )

  def configurations(ref: Reference, state: State): Seq[Configuration] =
    setting(NetBeansKeys.configurations in ref, state).fold(
      _ => Seq(Configurations.Compile, Configurations.Test),
      _.toSeq
    )

  def createSrc(ref: Reference, state: State)(configuration: Configuration): NetBeansCreateSrc.ValueSet =
    setting(NetBeansKeys.createSrc in (ref, configuration), state).fold(_ => NetBeansCreateSrc.All, id)

  def projectFlavor(ref: Reference, state: State) =
    setting(NetBeansKeys.projectFlavor in ref, state).fold(_ => NetBeansProjectFlavor.Scala, id)

  def netbeansOutput(ref: ProjectRef, state: State)(config: Configuration): Option[String] =
    setting(NetBeansKeys.netbeansOutput in (ref, config), state).fold(_ => None, id)

  def preTasks(ref: ProjectRef, state: State): Seq[(TaskKey[_], ProjectRef)] =
    setting(NetBeansKeys.preTasks in ref, state).fold(_ => Seq.empty, _.zipAll(Seq.empty, null, ref))

  def relativizeLibs(ref: ProjectRef, state: State): Boolean =
    setting(NetBeansKeys.relativizeLibs in ref, state).fold(_ => true, id)

  def skip(ref: ProjectRef, state: State): Boolean =
    setting(NetBeansKeys.skipProject in ref, state).fold(_ => false, id)

  // IO

  def saveXml(file: File, xml: Node): IO[Unit] =
    fileWriter(file).bracket(closeWriter)(writer => io(writer.write(new PrettyPrinter(999, 2) format xml)))

  def saveProperties(file: File, settings: Seq[(String, String)]): IO[Unit] =
    if (!settings.isEmpty) {
      val properties = new Properties
      for ((key, value) <- settings) properties.setProperty(key, value)
      fileWriterMkdirs(file).bracket(closeWriter)(writer =>
        io(properties.store(writer, "Generated by nbsbt")))
    } else
      io(())

  def fileWriter(file: File): IO[FileWriter] =
    io(new FileWriter(file))

  def fileWriterMkdirs(file: File): IO[FileWriter] =
    io {
      file.getParentFile.mkdirs()
      new FileWriter(file)
    }

  def closeWriter(writer: Writer): IO[Unit] =
    io(writer.close())

  private def io[T](t: => T): IO[T] = scalaz.effect.IO(t)

  // Utilities

  // Note: Relativize doesn't take into account "..", so we need to normalize *first* (yippie), then check for relativize.
  // Also - Instead of failure we should generate a "link".
  def relativize(baseDirectory: File, file: File): Option[String] =
    IO.relativize(baseDirectory, file)

  def relativizeOpt(baseDirectory: File, file: File): Option[String] =
    IO.relativize(baseDirectory, normalize(file))

  def normalize(file: File): File =
    new java.io.File(normalizeName(file.getAbsolutePath))

  def normalizeName(filename: String): String = {
    if (filename contains "..") {
      val parts = (filename split "[\\/]+").toList
      def fix(parts: List[String], result: String): String = parts match {
        case Nil                         => result
        case a :: ".." :: rest           => fix(rest, result)
        case a :: rest if result.isEmpty => fix(rest, a)
        case a :: rest                   => fix(rest, result + java.io.File.separator + a)
      }
      fix(parts, "")
    } else filename
  }
}

private case class Content(
  name:          String,
  dir:           File,
  project:       Node,
  classpath:     Node,
  scalacOptions: Seq[(String, String)]
)

private case class Lib(binary: File)(val source: Option[File])
private case class Prj(name: String, baseDirectory: File, classDirectory: Option[File])
