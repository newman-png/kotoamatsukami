package koto.core.ai

import koto.core.plan.Fixtures
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaTest {
    @Test
    fun `types, enums, extra keys and missing keys are all caught`() {
        val v = JsonSchema.validate(
            Schemas.CRITIC,
            Json.parseToJsonElement("""{"problems": ["a", 3], "score": "high", "mood": "fine"}"""),
        )
        assertTrue("\$.problems[1] must be a string" in v, v.toString())
        assertTrue("\$.score must be a whole number" in v, v.toString())
        assertTrue("\$.mood is not allowed" in v, v.toString())
        assertEquals(listOf("\$.score is missing"), JsonSchema.validate(Schemas.CRITIC, Json.parseToJsonElement("""{"problems": []}""")))
    }

    @Test
    fun `the answer is found inside thinking and fences`() {
        assertIs<JsonSchema.Parsed.Ok>(JsonSchema.parse("<think>{not this}</think>\n```json\n{\"problems\": [], \"score\": 7}\n```", Schemas.CRITIC))
        assertIs<JsonSchema.Parsed.Bad>(JsonSchema.parse("no json here", Schemas.CRITIC))
    }

    @Test
    fun `deadline ids are a closed list when there are deadlines`() {
        val s = Schemas.intent(Fixtures.profile).toString()
        assertTrue("\"enum\":[\"d1\",\"d2\"]" in s, s)
        val none = Schemas.intent(Fixtures.profile.copy(deadlines = emptyList())).toString()
        assertTrue("\"deadlineIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}" in none, none)
    }

    @Test
    fun `every prompt loads and fills completely`() {
        for (name in listOf(Prompts.PLANNER_SYSTEM, Prompts.CRITIC_SYSTEM, Prompts.WRITER_SYSTEM)) {
            assertTrue(Prompts.text(name).length > 200)
            assertTrue("{{" !in Prompts.text(name))
        }
        val filled = Prompts.fill(Prompts.REPAIR, mapOf("errors" to "- x"))
        assertTrue("- x" in filled)
        val missing = runCatching { Prompts.fill(Prompts.REPAIR, emptyMap()) }
        assertTrue(missing.isFailure)
    }
}
