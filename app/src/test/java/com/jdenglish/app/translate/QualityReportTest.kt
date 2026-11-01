package com.jdenglish.app.translate

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Offline translation quality harness (JVM). Loads the real glossary JSON files straight from
 * app/src/main/assets/glossary, builds Glossary + RuleEngine + EngineLogic.composeSegments the same
 * way TranslationEngine's local tiers do, and runs them over tools/harvest/strings.tsv (strings
 * harvested from live JD pages). Writes tools/quality/report.md and tools/quality/resolved.tsv.
 *
 * Fails only on glossary integrity problems (conflicting duplicate keys across files, Han in
 * values); coverage numbers are reported, not asserted.
 */
class QualityReportTest {

    private class Row(val count: Int, val kind: String, val text: String, val url: String)

    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "tools/harvest/strings.tsv").isFile && File(d, "app/src/main/assets/glossary").isDirectory) return d
            d = d.parentFile
        }
        error("repo root not found from ${System.getProperty("user.dir")}")
    }

    @Test fun offlineCoverageReport() {
        val root = repoRoot()
        val dir = File(root, "app/src/main/assets/glossary")
        val jsonFiles = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        val texts = jsonFiles.map { it.name to it.readText(Charsets.UTF_8) }

        // ---- integrity: cross-file duplicates (same normalized key in two files)
        val seen = HashMap<String, Pair<String, String>>() // key -> (file, value)
        val conflicts = ArrayList<String>()
        val identicalDupes = ArrayList<String>()
        val hanValues = ArrayList<String>()
        for ((name, text) in texts) {
            val obj = JSONObject(text)
            for (k in obj.keys()) {
                val nk = Glossary.normalize(k)
                val v = Glossary.normalize(obj.getString(k))
                if (Cjk.hasHan(v)) hanValues += "$name: $nk -> $v"
                val prev = seen[nk]
                if (prev != null && prev.first != name) {
                    if (prev.second == v) identicalDupes += "$nk (${prev.first}, $name)"
                    else conflicts += "$nk: ${prev.first}=\"${prev.second}\" vs $name=\"$v\""
                }
                seen[nk] = name to v
            }
        }

        val map = Glossary.parseFiles(texts)
        val protRaw = File(dir, "_protected.txt").takeIf { it.isFile }?.readText()
        val g = Glossary(map, Glossary.parseProtected(protRaw, map))
        val rules = RuleEngine { g.lookup(it) }

        fun resolveLocal(norm: String): String? =
            g.lookup(norm) ?: rules.apply(norm)?.takeIf { it.isNotBlank() }

        fun translate(raw: String): Pair<String?, String> {
            val norm = Glossary.normalize(raw)
            g.lookup(norm)?.let { return it to "glossary" }
            rules.apply(norm)?.takeIf { it.isNotBlank() }?.let { return it to "rule" }
            EngineLogic.composeSegments(norm) { resolveLocal(it) }?.let { return it to "segments" }
            return null to "-"
        }

        val rows = File(root, "tools/harvest/strings.tsv").readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            val n = p[0].toIntOrNull() ?: return@mapNotNull null
            Row(n, p[1], p[2], p.getOrElse(3) { "" })
        }.filter { Cjk.hasHan(it.text) }

        // Dedupe by normalized text (same string harvested as text and as attribute).
        val byText = LinkedHashMap<String, Row>()
        for (r in rows) {
            val k = Glossary.normalize(r.text)
            val prev = byText[k]
            byText[k] = if (prev == null) r else Row(prev.count + r.count, prev.kind, prev.text, prev.url)
        }
        val all = byText.values.toList()
        val results = all.associateWith { translate(it.text) }

        class Stat(val label: String, val set: List<Row>) {
            val ok = set.filter { results[it]!!.first != null }
            val n = set.size
            val w = set.sumOf { it.count }
            val okN = ok.size
            val okW = ok.sumOf { it.count }
            fun pct(a: Int, b: Int) = if (b == 0) "-" else String.format(Locale.US, "%.1f%%", 100.0 * a / b)
            fun line() = "| $label | $okN / $n | ${pct(okN, n)} | $okW / $w | ${pct(okW, w)} |"
        }
        val len = { r: Row -> Glossary.normalize(r.text).length }
        val stats = listOf(
            Stat("all strings", all),
            Stat("short (<= 12 chars)", all.filter { len(it) <= 12 }),
            Stat("short (<= 6 chars)", all.filter { len(it) <= 6 }),
            Stat("medium (13-40 chars)", all.filter { len(it) in 13..40 }),
            Stat("long (> 40 chars, MT by design)", all.filter { len(it) > 40 }),
        )
        val byTier = results.values.groupingBy { it.second }.eachCount()

        val sb = StringBuilder()
        sb.append("# Offline translation coverage report\n\n")
        sb.append("Generated by `QualityReportTest` (`./gradlew :app:testReleaseUnitTest --tests '*QualityReportTest*'`).\n")
        sb.append("Input: `tools/harvest/strings.tsv` (${rows.size} rows, ${all.size} distinct Han strings). ")
        sb.append("Glossary: ${g.size} terms from ${jsonFiles.size} files, ${g.protectedTerms.size} protected terms.\n\n")
        sb.append("Offline = glossary exact match, RuleEngine, or EngineLogic.composeSegments (the engine's local tiers).\n\n")
        sb.append("| set | resolved (distinct) | % | resolved (count-weighted) | % weighted |\n|---|---|---|---|---|\n")
        stats.forEach { sb.append(it.line()).append('\n') }
        sb.append("\nResolved by tier: ").append(byTier.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }).append("\n\n")

        sb.append("## Glossary integrity\n\n")
        sb.append("- Conflicting duplicate keys across files: ${conflicts.size}\n")
        conflicts.forEach { sb.append("  - $it\n") }
        sb.append("- Identical duplicate keys across files: ${identicalDupes.size}\n")
        identicalDupes.take(50).forEach { sb.append("  - $it\n") }
        sb.append("- Values containing Han: ${hanValues.size}\n")
        hanValues.forEach { sb.append("  - $it\n") }

        val unresolved = all.filter { results[it]!!.first == null }.sortedWith(compareByDescending<Row> { it.count }.thenBy { len(it) })
        val unShort = unresolved.filter { len(it) <= 12 }
        sb.append("\n## Unresolved short strings (<= 12 chars): ${unShort.size}\n\n| count | kind | text | url |\n|---|---|---|---|\n")
        unShort.forEach { sb.append("| ${it.count} | ${it.kind} | ${md(it.text)} | ${it.url.take(70)} |\n") }
        val unLonger = unresolved.filter { len(it) > 12 }
        sb.append("\n## Unresolved longer strings (> 12 chars; mostly product titles/descriptions for MT): ${unLonger.size}\n\n")
        sb.append("| count | text |\n|---|---|\n")
        unLonger.take(400).forEach { sb.append("| ${it.count} | ${md(it.text.take(80))} |\n") }
        if (unLonger.size > 400) sb.append("\n... ${unLonger.size - 400} more\n")

        val outDir = File(root, "tools/quality").apply { mkdirs() }
        File(outDir, "report.md").writeText(sb.toString(), Charsets.UTF_8)
        // Every offline translation, for human review.
        File(outDir, "resolved.tsv").writeText(
            "count\ttier\ttext\tenglish\n" + all.filter { results[it]!!.first != null }
                .sortedByDescending { it.count }
                .joinToString("") { "${it.count}\t${results[it]!!.second}\t${it.text}\t${results[it]!!.first}\n" },
            Charsets.UTF_8,
        )
        println(stats.joinToString("\n") { it.line() })

        assertTrue("Conflicting cross-file glossary keys:\n" + conflicts.joinToString("\n"), conflicts.isEmpty())
        assertTrue("Glossary values containing Han:\n" + hanValues.joinToString("\n"), hanValues.isEmpty())
    }

    private fun md(s: String) = s.replace("|", "\\|")
}
