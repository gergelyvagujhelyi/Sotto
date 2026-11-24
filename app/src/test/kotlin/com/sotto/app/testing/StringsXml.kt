package com.sotto.app.testing

import java.io.File

/**
 * A language's strings as its files in res/ write them, for tests that must check the real
 * translations rather than copies (unit tests run in the app module, without Android resources).
 */
class StringsXml(values: String) {
    private val strings: Map<String, String> =
        File("src/main/res/$values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!
            .flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to unescape(it.groupValues[2]) } }
            .toMap()

    operator fun get(name: String): String = strings[name] ?: error("No string $name")

    private companion object {
        val STRING = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        fun unescape(raw: String) = raw.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

val English = StringsXml("values")
val Hungarian = StringsXml("values-hu")
