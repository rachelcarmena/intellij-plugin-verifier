package com.jetbrains.pluginverifier.persistence

import com.github.salomonbrys.kotson.*
import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.structure.ide.IdeVersion
import com.intellij.structure.impl.domain.PluginDependencyImpl
import com.intellij.structure.plugin.PluginDependency
import com.jetbrains.pluginverifier.api.Verdict
import com.jetbrains.pluginverifier.dependencies.DependenciesGraph
import com.jetbrains.pluginverifier.location.Location
import com.jetbrains.pluginverifier.problems.*
import com.jetbrains.pluginverifier.reference.SymbolicReference
import com.jetbrains.pluginverifier.utils.*
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object CompactJson {

  fun toJson(src: Any): String = GSON.toJson(src)

  fun <T> fromJson(json: String, type: Type): T = GSON.fromJson<T>(json, type)

  inline fun <reified T : Any> fromJson(json: String): T = fromJson(json, typeToken<T>())

  private val GSON: Gson = GsonBuilder()
      //serializes map as Json-array instead of Json-object
      .enableComplexMapKeySerialization()
      .registerTypeHierarchyAdapter(IdeVersion::class.java, IdeVersionTypeAdapter().nullSafe())

      .registerTypeHierarchyAdapter<PluginDependency> {
        serialize {
          jsonArray(it.src.id, it.src.isOptional)
        }
        deserialize {
          val array = it.json.asJsonArray
          PluginDependencyImpl(array[0].string, array[1].bool)
        }
      }
      .registerTypeHierarchyAdapter<Location>(problemLocationSerializer)
      .registerTypeHierarchyAdapter<Location>(problemLocationDeserializer)
      .registerTypeHierarchyAdapter<SymbolicReference>(symbolicReferenceSerializer)
      .registerTypeHierarchyAdapter<SymbolicReference>(symbolicReferenceDeserializer)

      .registerTypeAdapterFactory(verdictTAF)
      .registerTypeAdapterFactory(problemsTAF)
      .registerTypeAdapterFactory(MultimapTypeAdapterFactory())
      .registerTypeAdapterFactory(PairTypeAdapterFactory())
      .registerTypeAdapterFactory(TripleTypeAdapterFactory())

      .registerTypeAdapter<DependenciesGraph>(dependenciesGraphSerializer)
      .registerTypeAdapter<DependenciesGraph>(dependenciesGraphDeserializer)
      .create()
}

private val verdictTAF = RuntimeTypeAdapterFactory.of(Verdict::class.java)
    .registerSubtype(Verdict.OK::class.java)
    .registerSubtype(Verdict.Warnings::class.java)
    .registerSubtype(Verdict.MissingDependencies::class.java)
    .registerSubtype(Verdict.Problems::class.java)
    .registerSubtype(Verdict.Bad::class.java)
    .registerSubtype(Verdict.NotFound::class.java)

//add inheritors
private val problemsTAF = RuntimeTypeAdapterFactory.of(Problem::class.java)
    .registerSubtype(ChangeFinalFieldProblem::class.java)
    .registerSubtype(NonStaticAccessOfStaticFieldProblem::class.java)
    .registerSubtype(InvokeStaticOnNonStaticMethodProblem::class.java)
    .registerSubtype(InvokeNonStaticInstructionOnStaticMethodProblem::class.java)
    .registerSubtype(StaticAccessOfNonStaticFieldProblem::class.java)
    .registerSubtype(AbstractClassInstantiationProblem::class.java)
    .registerSubtype(ClassNotFoundProblem::class.java)
    .registerSubtype(FieldNotFoundProblem::class.java)
    .registerSubtype(IllegalFieldAccessProblem::class.java)
    .registerSubtype(IllegalMethodAccessProblem::class.java)
    .registerSubtype(SuperClassBecameInterfaceProblem::class.java)
    .registerSubtype(InvokeClassMethodOnInterfaceProblem::class.java)
    .registerSubtype(SuperInterfaceBecameClassProblem::class.java)
    .registerSubtype(InvokeInterfaceMethodOnClassProblem::class.java)
    .registerSubtype(InterfaceInstantiationProblem::class.java)
    .registerSubtype(InvokeInterfaceOnPrivateMethodProblem::class.java)
    .registerSubtype(MethodNotFoundProblem::class.java)
    .registerSubtype(MethodNotImplementedProblem::class.java)
    .registerSubtype(AbstractMethodInvocationProblem::class.java)
    .registerSubtype(OverridingFinalMethodProblem::class.java)
    .registerSubtype(InheritFromFinalClassProblem::class.java)
    .registerSubtype(IllegalClassAccessProblem::class.java)
    .registerSubtype(MultipleDefaultImplementationsProblem::class.java)
    .registerSubtype(InvalidClassFileProblem::class.java)


class IdeVersionTypeAdapter : TypeAdapter<IdeVersion>() {

  @Throws(IOException::class)
  override fun write(out: JsonWriter, value: IdeVersion) {
    out.value(value.asString())
  }

  @Throws(IOException::class)
  override fun read(`in`: JsonReader): IdeVersion {
    return IdeVersion.createIdeVersion(`in`.nextString())
  }

}

class PairTypeAdapterFactory : TypeAdapterFactory {

  override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
    val type = typeToken.type
    if (!Pair::class.java.isAssignableFrom(typeToken.rawType) || type !is ParameterizedType) {
      return null
    }
    val types = type.actualTypeArguments
    @Suppress("UNCHECKED_CAST")
    return newPairAdapter(gson.getAdapter(TypeToken.get(types[0])), gson.getAdapter(TypeToken.get(types[1]))) as TypeAdapter<T>
  }

  private fun <A, B> newPairAdapter(firstAdapter: TypeAdapter<A>, secondAdapter: TypeAdapter<B>): TypeAdapter<Pair<A, B>> =
      object : TypeAdapter<Pair<A, B>>() {
        override fun write(out: JsonWriter, value: Pair<A, B>) {
          out.beginArray()
          firstAdapter.write(out, value.first)
          secondAdapter.write(out, value.second)
          out.endArray()
        }

        override fun read(`in`: JsonReader): Pair<A, B> {
          `in`.beginArray()
          val first = firstAdapter.read(`in`)
          val second = secondAdapter.read(`in`)
          `in`.endArray()
          return first to second
        }

      }.nullSafe()

}


class TripleTypeAdapterFactory : TypeAdapterFactory {
  override fun <T : Any?> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
    val type = typeToken.type
    if (!Triple::class.java.isAssignableFrom(typeToken.rawType) || type !is ParameterizedType) {
      return null
    }
    val types = type.actualTypeArguments
    @Suppress("UNCHECKED_CAST")
    return newTripleAdapter(gson.getAdapter(TypeToken.get(types[0])), gson.getAdapter(TypeToken.get(types[1])), gson.getAdapter(TypeToken.get(types[2]))) as TypeAdapter<T>
  }

  private fun <A, B, C> newTripleAdapter(firstAdapter: TypeAdapter<A>, secondAdapter: TypeAdapter<B>, thirdAdapter: TypeAdapter<C>): TypeAdapter<Triple<A, B, C>> =
      object : TypeAdapter<Triple<A, B, C>>() {
        override fun write(out: JsonWriter, value: Triple<A, B, C>) {
          out.beginArray()
          firstAdapter.write(out, value.first)
          secondAdapter.write(out, value.second)
          thirdAdapter.write(out, value.third)
          out.endArray()
        }

        override fun read(`in`: JsonReader): Triple<A, B, C> {
          `in`.beginArray()
          val first = firstAdapter.read(`in`)
          val second = secondAdapter.read(`in`)
          val third = thirdAdapter.read(`in`)
          `in`.endArray()
          return Triple(first, second, third)
        }

      }.nullSafe()

}

/**
 * The class allows to serialize all the `Multimap&lt;K, V&gt;` generified classes.
 */
class MultimapTypeAdapterFactory : TypeAdapterFactory {
  override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
    val type = typeToken.type
    if (!Multimap::class.java.isAssignableFrom(typeToken.rawType) || type !is ParameterizedType) {
      return null
    }
    val types = type.actualTypeArguments
    val keyType = types[0]
    val valueType = types[1]
    val keyAdapter = gson.getAdapter(TypeToken.get(keyType))
    val valueAdapter = gson.getAdapter(TypeToken.get(valueType))
    @Suppress("UNCHECKED_CAST")
    return newMultimapAdapter(keyAdapter, valueAdapter) as TypeAdapter<T>
  }

  private fun <K, V> newMultimapAdapter(keyAdapter: TypeAdapter<K>, valueAdapter: TypeAdapter<V>): TypeAdapter<Multimap<K, V>> {
    return object : TypeAdapter<Multimap<K, V>>() {
      @Throws(IOException::class)
      override fun write(out: JsonWriter, value: Multimap<K, V>) {
        out.beginArray()
        for (k in value.keySet()) {
          keyAdapter.write(out, k)
          out.beginArray()
          for (v in value.get(k)) {
            valueAdapter.write(out, v)
          }
          out.endArray()
        }
        out.endArray()
      }

      @Throws(IOException::class)
      override fun read(`in`: JsonReader): Multimap<K, V> {
        val result = LinkedHashMultimap.create<K, V>()
        `in`.beginArray()
        while (`in`.hasNext()) {
          val k = keyAdapter.read(`in`)
          `in`.beginArray()
          while (`in`.hasNext()) {
            val v = valueAdapter.read(`in`)
            result.put(k, v)
          }
          `in`.endArray()
        }

        `in`.endArray()
        return result
      }
    }.nullSafe() //Gson will check nulls automatically
  }
}