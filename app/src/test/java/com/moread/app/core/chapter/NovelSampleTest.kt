package com.moread.app.core.chapter

import com.moread.app.core.model.RulePattern
import com.moread.app.core.model.TocLevel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 回归：真实小说样本（章节标记带全角缩进、中英文数字混用）必须按章切分。 */
class NovelSampleTest {
    @Test
    fun `缩进章节标记能被识别`() {
        val f = File(javaClass.classLoader!!.getResource("novel_sample.txt")!!.toURI())
        val arr = org.json.JSONObject(File("src/main/assets/toc_rules.json").readText()).getJSONArray("rules")
        val rules = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            runCatching {
                RulePattern(
                    id = o.optLong("id", i.toLong()),
                    name = o.optString("name"),
                    regex = Regex(o.getString("pattern")),
                    level = if (o.optString("level") == "VOLUME") TocLevel.VOLUME else TocLevel.CHAPTER,
                    enabled = o.optBoolean("enabled", true),
                    sortOrder = o.optInt("sortOrder", 100),
                    builtIn = o.optBoolean("builtIn", false),
                )
            }.getOrNull()
        }
        val (_, toc) = kotlinx.coroutines.runBlocking { com.moread.app.core.parser.TxtParser().parse(f, rules) }
        val n = toc.result.chapters.size
        println("chapters=[$n] first=[${toc.result.chapters.first().title}] last=[${toc.result.chapters.last().title}]")
        assertTrue("应识别出约 30 章，实际 $n", n in 25..60)
        assertTrue(toc.result.chapters.first().title.contains("第一章"))
    }
}
