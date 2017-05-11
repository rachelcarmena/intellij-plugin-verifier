package com.jetbrains.intellij.feature.extractor.core

import com.intellij.structure.resolvers.Resolver
import org.jetbrains.intellij.plugins.internal.asm.tree.ClassNode
import org.jetbrains.intellij.plugins.internal.asm.tree.MethodInsnNode
import org.jetbrains.intellij.plugins.internal.asm.tree.MethodNode
import org.jetbrains.intellij.plugins.internal.asm.tree.analysis.Analyzer
import org.jetbrains.intellij.plugins.internal.asm.tree.analysis.Frame
import org.jetbrains.intellij.plugins.internal.asm.tree.analysis.SourceInterpreter
import org.jetbrains.intellij.plugins.internal.asm.tree.analysis.Value

/*
 * Extracts value returned by FacetType#getStringId from a class extending FacetType.
*/
class FacetTypeExtractor(resolver: Resolver) : Extractor(resolver) {

  private val FACET_TYPE = "com/intellij/facet/FacetType"

  override fun extractImpl(classNode: ClassNode): List<String>? {
    if (classNode.superName != FACET_TYPE) {
      return null
    }

    @Suppress("UNCHECKED_CAST")
    (classNode.methods as List<MethodNode>).filter { it.name == "<init>" }.forEach { initMethod ->
      val interpreter = SourceInterpreter()
      val frames: List<Frame> = Analyzer(interpreter).analyze(classNode.name, initMethod).toList()

      initMethod.instructions.toArray().forEachIndexed { index, insn ->
        if (insn is MethodInsnNode) {
          if (insn.name == "<init>" && insn.owner == FACET_TYPE) {

            val frame: Frame = frames[index]

            val value: Value?
            if (insn.desc == "(Lcom/intellij/facet/FacetTypeId;Ljava/lang/String;Ljava/lang/String;Lcom/intellij/facet/FacetTypeId;)V") {
              value = frame.getOnStack(2)
            } else if (insn.desc == "(Lcom/intellij/facet/FacetTypeId;Ljava/lang/String;Ljava/lang/String;)V") {
              value = frame.getOnStack(1)
            } else {
              return@forEachIndexed
            }

            val stringValue = AnalysisUtil.evaluateConstantString(value, resolver, frames, initMethod.instructionsAsList())
            if (stringValue != null) {
              extractedAll = true
              return listOf(stringValue)
            }
          }
        }
      }
    }
    return null
  }
}