package com.sysadmin.lasstore.ui.catalog

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test
    fun rendersHeadingsListsAndInlineEmphasisAsAnnotatedText() {
        val rendered = markdownToAnnotatedString("# Title\n- **fix** `code` *note*")

        assertTrue(rendered.text.contains("Title"))
        assertTrue(rendered.text.contains("• fix code note"))
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
}
