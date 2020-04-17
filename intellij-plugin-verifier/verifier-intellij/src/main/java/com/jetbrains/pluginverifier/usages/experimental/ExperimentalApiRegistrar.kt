/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.usages.experimental

interface ExperimentalApiRegistrar {
  fun registerExperimentalApiUsage(experimentalApiUsage: ExperimentalApiUsage)
}