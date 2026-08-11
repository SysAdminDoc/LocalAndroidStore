package com.sysadmin.lasstore.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmin.lasstore.R
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PseudolocaleInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun enXaResourcesAreGeneratedAndExpanded() {
        val source = context.getString(R.string.trust_matrix_pseudolocale_probe)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("en-XA")))
        }
        val pseudo = context.createConfigurationContext(configuration)
            .getString(R.string.trust_matrix_pseudolocale_probe)

        assertNotEquals(source, pseudo)
        assertTrue(pseudo.length > source.length)
    }

    @Test
    fun catalogCopyUsesLocalizedQuantitiesAndFormatting() {
        assertEquals("app", context.resources.getQuantityString(R.plurals.catalog_app_count, 1))
        assertEquals("apps", context.resources.getQuantityString(R.plurals.catalog_app_count, 2))
        assertEquals(
            "1 of 3 releases",
            context.getString(R.string.catalog_release_count, 1, 3),
        )
    }
}
