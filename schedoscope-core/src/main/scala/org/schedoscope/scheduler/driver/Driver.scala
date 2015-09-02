/**
 * Copyright 2015 Otto (GmbH & Co KG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.scheduler.driver

import java.nio.file.Files
import scala.Array.canBuildFrom
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Random
import org.joda.time.LocalDateTime
import org.schedoscope.DriverSettings
import org.schedoscope.dsl.Transformation
import net.lingala.zip4j.core.ZipFile
import org.schedoscope.Settings
import org.apache.commons.io.FileUtils

case class DriverException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

class DriverRunHandle[T <: Transformation](val driver: Driver[T], val started: LocalDateTime, val transformation: T, val stateHandle: Any)

sealed abstract class DriverRunState[T <: Transformation](val driver: Driver[T])
case class DriverRunOngoing[T <: Transformation](override val driver: Driver[T], val runHandle: DriverRunHandle[T]) extends DriverRunState[T](driver)
case class DriverRunSucceeded[T <: Transformation](override val driver: Driver[T], comment: String) extends DriverRunState[T](driver)
case class DriverRunFailed[T <: Transformation](override val driver: Driver[T], reason: String, cause: Throwable) extends DriverRunState[T](driver)

trait DriverRunCompletionHandler[T <: Transformation] {
  def driverRunCompleted(stateOfCompletion: DriverRunState[T], run: DriverRunHandle[T])
}

class DoNothingCompletionHandler[T <: Transformation] extends DriverRunCompletionHandler[T] {
  def driverRunCompleted(stateOfCompletion: DriverRunState[T], run: DriverRunHandle[T]) {}
}

trait Driver[T <: Transformation] {
  def transformationName: String

  def runTimeOut: Duration = Settings().getDriverSettings(transformationName).timeout

  def killRun(run: DriverRunHandle[T]): Unit = {}

  def getDriverRunState(run: DriverRunHandle[T]): DriverRunState[T] = {
    val runState = run.stateHandle.asInstanceOf[Future[DriverRunState[T]]]
    if (runState.isCompleted)
      runState.value.get.get
    else
      DriverRunOngoing[T](this, run)
  }

  def run(t: T): DriverRunHandle[T]

  def runAndWait(t: T): DriverRunState[T] = Await.result(run(t).stateHandle.asInstanceOf[Future[DriverRunState[T]]], runTimeOut)

  def deployAll(ds: DriverSettings): Boolean = {
    val fsd = FileSystemDriver(ds)

    // clear destination
    fsd.delete(ds.location, true)
    fsd.mkdirs(ds.location)

    val succ = ds.libJars
      .map(f => {
        if (ds.unpack) {
          val tmpDir = Files.createTempDirectory("schedoscope-" + Random.nextLong.abs.toString).toFile
          new ZipFile(f.replaceAll("file:", "")).extractAll(tmpDir.getAbsolutePath)
          val succ = fsd.copy("file://" + tmpDir + "/*", ds.location, true)
          FileUtils.deleteDirectory(tmpDir)
          succ
        } else {
          fsd.copy(f, ds.location, true)
        }
      })

    succ.filter(_.isInstanceOf[DriverRunFailed[_]]).isEmpty
  }

  def driverRunCompletionHandlerClassNames: List[String]

  lazy val driverRunCompletionHandlers: List[DriverRunCompletionHandler[T]] = try {
    driverRunCompletionHandlerClassNames.map { className => Class.forName(className).newInstance().asInstanceOf[DriverRunCompletionHandler[T]] }
  } catch {
    case t: Throwable => throw DriverException("Driver run completion handler could not be instantiated", t)
  }

  def driverRunCompleted(run: DriverRunHandle[T]) {
    getDriverRunState(run) match {
      case s: DriverRunSucceeded[T] => driverRunCompletionHandlers.foreach(_.driverRunCompleted(s, run))
      case f: DriverRunFailed[T] => driverRunCompletionHandlers.foreach(_.driverRunCompleted(f, run))
      case _ => throw DriverException("driverRunCompleted called with non-final driver run state")
    }
  }
}