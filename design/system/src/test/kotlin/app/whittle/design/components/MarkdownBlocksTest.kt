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
}
