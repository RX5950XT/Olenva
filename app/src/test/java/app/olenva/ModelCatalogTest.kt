package app.olenva

import app.olenva.net.ModelInfo
import app.olenva.settings.groupByLab
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogTest {
    private val day = 24 * 3600L
    private val now = 1_800_000_000L

    private fun m(
        id: String, provider: String, ageDays: Long,
        input: List<String> = listOf("text", "image"), output: List<String> = listOf("text"),
        params: List<String> = listOf("reasoning", "tools"), ctx: Int = 200_000,
    ) = ModelInfo(id, id, provider, ctx, input, null, null, params, created = now - ageDays * day, outputModalities = output)

    @Test fun keepsRecentUsableModelsMajorLabsFirstNewestFirst() {
        val groups = groupByLab(
            listOf(
                m("openai/old", "OpenAI", 100),
                m("openai/new", "OpenAI", 10),
                m("google/b", "Google", 80),
                m("google/a", "Google", 5),
                m("~google/alias", "Google", 1),
                m("acme/x", "Acme", 3),
                m("xiaomi/mimo", "Xiaomi", 2),
                m("meta-llama/l", "Meta", 4),
                m("qwen/no-image", "Qwen", 1, input = listOf("text")),
                m("qwen/no-reason", "Qwen", 1, params = listOf("tools")),
                m("qwen/no-tools", "Qwen", 1, params = listOf("reasoning")),
                m("qwen/small-ctx", "Qwen", 1, ctx = 32_768),
                m("google/image-gen", "Google", 1, output = listOf("image", "text")),
                m("google/a:batch", "Google", 1),
            ),
            now,
        )
        assertEquals(listOf("Google", "OpenAI", "Meta", "Xiaomi", "Acme"), groups.map { it.first })
        assertEquals(listOf("~google/alias", "google/a", "google/b"), groups[0].second.map { it.id })
        assertEquals(listOf("openai/new"), groups[1].second.map { it.id })
    }

}
