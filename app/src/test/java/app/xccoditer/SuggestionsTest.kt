package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class SuggestionsTest {
    @Test
    fun prefixStopsAtNonIdentifier() {
        assertEquals("pri", Suggestions.prefixAt("x = pri", 7))
        assertEquals("", Suggestions.prefixAt("x = ", 4))
        assertEquals("", Suggestions.prefixAt("foo.", 4))
    }

    @Test
    fun pythonPrintSnippetComesFirst() {
        val s = Suggestions.suggest("pri", 3, Lang.PYTHON)
        assertEquals("print", s.first().label)
        assertEquals(SuggestionKind.SNIPPET, s.first().kind)
    }

    @Test
    fun keywordsAndDocumentWords() {
        val text = "my_variable = 1\nmy_"
        val labels = Suggestions.suggest(text, text.length, Lang.PYTHON).map { it.label }
        assertTrue("my_variable" in labels)
        val kw = Suggestions.suggest("ret", 3, Lang.PYTHON).map { it.label }
        assertTrue("return" in kw)
    }

    @Test
    fun fullyTypedKeywordNotOffered() {
        assertTrue(Suggestions.suggest("return", 6, Lang.PYTHON).none { it.label == "return" })
    }

    @Test
    fun emptyOrNumericPrefixGivesNothing() {
        assertTrue(Suggestions.suggest("x = ", 4, Lang.PYTHON).isEmpty())
        assertTrue(Suggestions.suggest("12", 2, Lang.PYTHON).isEmpty())
    }

    @Test
    fun limitRespected() {
        assertTrue(Suggestions.suggest("a", 1, Lang.JAVASCRIPT, limit = 3).size <= 3)
    }

    @Test
    fun applyReplacesPrefixAndPlacesCursor() {
        val s = Suggestions.suggest("pri", 3, Lang.PYTHON).first()
        val (t, pos) = Suggestions.apply("pri", 3, s)
        assertEquals("print()", t)
        assertEquals(6, pos)
    }

    @Test
    fun applyKeepsIndentOnMultilineSnippet() {
        val text = "class A:\n    de"
        val s = Suggestions.suggest(text, text.length, Lang.PYTHON).first { it.label == "def" }
        val (t, pos) = Suggestions.apply(text, text.length, s)
        assertEquals("class A:\n    def ():\n        ", t)
        assertEquals("class A:\n    def ".length, pos)
    }

    @Test
    fun applyInMiddleOfText() {
        val s = Suggestion("return", "return", SuggestionKind.KEYWORD)
        val (t, pos) = Suggestions.apply("ret x", 3, s)
        assertEquals("return x", t)
        assertEquals(6, pos)
    }

    @Test
    fun sqlKeywordsFollowTypedCase() {
        val labels = Suggestions.suggest("WH", 2, Lang.SQL).map { it.label }
        assertTrue("WHERE" in labels)
    }
}
