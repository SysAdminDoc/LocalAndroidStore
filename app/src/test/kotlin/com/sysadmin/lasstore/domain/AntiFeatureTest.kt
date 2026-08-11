package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFeatureTest {
    @Test
    fun knownKeysUseTaxonomyLabelsAndSeverity() {
        assertEquals(
            AntiFeatureBadge("Tracking", "Tracking", AntiFeatureSeverity.Danger),
            antiFeatureBadge("Tracking"),
        )
        assertEquals(
            AntiFeatureBadge("NonFreeDep", "Non-free dependencies", AntiFeatureSeverity.Warning),
            antiFeatureBadge("NonFreeDep"),
        )
        assertEquals(
            AntiFeatureBadge("TetheredNet", "Tethered network", AntiFeatureSeverity.Warning),
            antiFeatureBadge("TetheredNet"),
        )
    }

    @Test
    fun allPublishedKeysRemainVisibleInTaxonomyOrder() {
        val badges = antiFeatureBadges(
            listOf(
                "TetheredNet",
                "KnownVuln",
                "Ads",
                "DisabledAlgorithm",
                "Tracking",
                "NoSourceSince",
            ),
        )

        assertEquals(
            listOf("Ads", "Tracking", "NoSourceSince", "KnownVuln", "DisabledAlgorithm", "TetheredNet"),
            badges.map { it.key },
        )
        assertEquals(3, badges.count { it.severity == AntiFeatureSeverity.Danger })
    }

    @Test
    fun unknownAndBlankValuesAreHandledSafely() {
        val unknown = antiFeatureBadge("FutureFlag")

        assertEquals("Future Flag", unknown?.label)
        assertEquals(AntiFeatureSeverity.Warning, unknown?.severity)
        assertNull(antiFeatureBadge("  "))
        assertTrue(antiFeatureBadges(listOf("Tracking", "tracking")).size == 1)
    }
}
