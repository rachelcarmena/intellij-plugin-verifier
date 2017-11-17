package com.jetbrains.pluginverifier.tests.repository

import com.jetbrains.pluginverifier.repository.files.FileKeyMapper
import java.io.File

class IntFileKeyMapper : FileKeyMapper<Int> {
  override val directoriesStored: Boolean = false

  override fun getKey(file: File): Int? = file.nameWithoutExtension.toInt()

  override fun getFileNameWithoutExtension(key: Int): String = key.toString()
}