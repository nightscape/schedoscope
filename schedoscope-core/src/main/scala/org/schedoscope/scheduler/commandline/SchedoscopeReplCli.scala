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
package org.schedoscope.scheduler.commandline

import java.io.File

import org.jline.reader._
import org.jline.reader.impl.history.DefaultHistory
import org.schedoscope.scheduler.service.SchedoscopeService

/**
  * A REPL for executing cli commands against a Schedoscope service implementation
  */
class SchedoscopeCliRepl(val schedoscope: SchedoscopeService) {
  def start() {
    val ctrl = new SchedoscopeCliCommandRunner(schedoscope)
    val history = new DefaultHistory()
    val histFileDir = Option(System.getenv("XDG_CACHE_HOME")).getOrElse(System.getenv("HOME") + "/.cache")
    val reader = LineReaderBuilder.builder.history(history).build
    reader.setVariable(LineReader.HISTORY_FILE, new File(histFileDir + "/schedoscope_history"))
    while (true) {
      try {
        val cmd = reader.readLine("schedoscope> ")
        // we have to intercept --help because otherwise jline seems to call System.exit :(
        if (cmd != null && !cmd.trim().replaceAll(";", "").isEmpty() && !cmd.matches(".*--help.*"))
          ctrl.run(cmd.split("\\s+"))
      } catch {
        case t: Throwable => println(s"ERROR: ${t.getMessage}\n\n"); t.printStackTrace()
      }
    }
  }
}
