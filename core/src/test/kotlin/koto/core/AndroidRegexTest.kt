package koto.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's regex engine is ICU, which is stricter than the JVM's: a bare "}" or "]" that the
 * JVM reads as a literal is a syntax error on the phone. These tests run on the JVM, so they
 * check every regex literal in the sources against ICU's rule instead of trusting the JVM.
 */
class AndroidRegexTest {

    @Test
    fun `the checker knows what ICU rejects`() {
        assertNotNull(problem("\\{\\{(\\w+)}}"), "the pattern that stopped the planner on the phone")
        assertNotNull(problem("\\[([A-Z_]+)]"))
        assertNotNull(problem("a{x}"))
        assertNull(problem("\\{\\{(\\w+)\\}\\}"))
        assertNull(problem("^(\\d{1,2}):(\\d{2})$"))
        assertNull(problem("[^}\\]]+"))
        assertNull(problem("x{2,}"))
    }

    @Test
    fun `every regex in the app and core is valid on Android`() {
        val root = repoRoot()
        val files = listOf("core/src/main", "app/src/main").map { File(root, it) }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.size > 20, "found only ${files.size} source files under $root")
        val found = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            for (m in LITERAL.findAll(text)) {
                val raw = m.groupValues[2].takeIf { m.groupValues[1] == "\"\"\"" } ?: unescapeKotlin(m.groupValues[2])
                problem(raw)?.let { found += "${f.relativeTo(root)}: Regex(\"$raw\"): $it" }
            }
        }
        assertEquals(emptyList(), found)
    }

    /** Null if ICU accepts [p]'s braces and brackets, else where it breaks. */
    private fun problem(p: String): String? {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> {
                    inClass = true
                    if (p.getOrNull(i + 1) == '^') i++
                    if (p.getOrNull(i + 1) == ']') i++
                }
                c == '{' -> {
                    // Only a quantifier may open a brace: {n}, {n,} or {n,m}.
                    var j = i + 1
                    while (j < p.length && p[j].isDigit()) j++
                    if (j == i + 1) return "unescaped '{' at $i"
                    if (p.getOrNull(j) == ',') {
                        j++
                        while (j < p.length && p[j].isDigit()) j++
                    }
                    if (p.getOrNull(j) != '}') return "unescaped '{' at $i"
                    i = j
                }
                c == '}' -> return "unescaped '}' at $i"
                c == ']' -> return "unescaped ']' at $i"
            }
            i++
        }
        return null
    }

    private fun unescapeKotlin(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                out.append(
                    when (val n = s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> n
                    },
                )
                i += 2
            } else {
                out.append(s[i++])
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        System.getProperty("koto.root")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("repository root not found")
    }

    private companion object {
        /** Regex("...") or Regex("""...""") as written in Kotlin source. */
        val LITERAL = Regex("Regex\\((\"\"\"|\")((?:\\\\.|[^\"\\\\])*?)\\1\\)")
    }
}
