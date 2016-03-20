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

package org.netbeans.nbsbt.plugin

import org.netbeans.nbsbt.core.{ NetBeansPlugin => NetBeansCorePlugin }
import sbt.{ AutoPlugin, Setting }

object NetBeansPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  override def trigger = allRequirements
  // Auto import semantics users expect today.
  val autoImport: NetBeansCorePlugin.type = NetBeansCorePlugin
  override def projectSettings: Seq[Setting[_]] = NetBeansCorePlugin.netbeansSettings
  override def buildSettings: Seq[Setting[_]] = NetBeansCorePlugin.buildNetBeansSettings
}
