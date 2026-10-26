package app.whittle.design.components

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownBlocksTest {
    @Test
    fun `a table between paragraphs becomes its own block`() {
        val text = "Here are three:\n\n| Laptop | Price | RAM |\n|---|:---:|--:|\n| **Dell 15** | ₹55,990 | 16 GB |\n| HP 15s | ₹49,990 |\n\nI'd pick the Dell."
        assertEquals(
            listOf(
                MarkdownBlock.Text("Here are three:"),
                MarkdownBlock.Table(
                    listOf("Laptop", "Price", "RAM"),
                    listOf(listOf("**Dell 15**", "₹55,990", "16 GB"), listOf("HP 15s", "₹49,990", "")),
                ),
                MarkdownBlock.Text("I'd pick the Dell."),
            ),
            MarkdownBlocks.parse(text),
        )
    }

    @Test
    fun `pipes without a separator row, or a table still streaming, stay text`() {
        assertEquals(listOf(MarkdownBlock.Text("a | b | c\n| x |")), MarkdownBlocks.parse("a | b | c\n| x |"))
        assertEquals(listOf(MarkdownBlock.Text("| Name | Age |")), MarkdownBlocks.parse("| Name | Age |"))
    }

    @Test
    fun `escaped pipes stay inside a cell`() {
        val table = MarkdownBlocks.parse("| A | B |\n|---|---|\n| x \\| y | z |").single() as MarkdownBlock.Table
        assertEquals(listOf("x | y", "z"), table.rows.single())
    }

    @Test
    fun `paragraphs split at blank lines but keep a code block whole`() {
        val text = "First line\nstill first.\n\nSecond.\n\n```\nval a = 1\n\nval b = 2\n```\n\n\nLast."
        assertEquals(
            listOf("First line\nstill first.", "Second.", "```\nval a = 1\n\nval b = 2\n```", "Last."),
            MarkdownBlocks.paragraphs(text),
        )
    }

    @Test
    fun `a code block still streaming keeps its blank lines`() {
        assertEquals(listOf("Here:", "```\nfun a()\n\nfun b()"), MarkdownBlocks.paragraphs("Here:\n\n```\nfun a()\n\nfun b()"))
    }

    @Test
    fun `a long paragraph splits into runs of lines unless it holds a code block`() {
        val list = (1..23).joinToString("\n") { "$it. Fact" }
        val runs = MarkdownBlocks.runs(list, maxLines = 10)
        assertEquals(listOf(10, 10, 3), runs.map { it.lines().size })
        assertEquals(list, runs.joinToString("\n"))
        val fenced = "```\n" + (1..12).joinToString("\n") { "line $it" } + "\n```"
        assertEquals(listOf(fenced), MarkdownBlocks.runs(fenced, maxLines = 10))
    }
}
