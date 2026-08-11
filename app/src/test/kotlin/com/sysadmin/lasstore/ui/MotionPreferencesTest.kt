package com.sysadmin.lasstore.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPreferencesTest {
    @Test
    fun zeroOrNegativeAnimatorScaleDisablesMotion() {
        assertTrue(reducedMotionForAnimatorScale(0f))
        assertTrue(reducedMotionForAnimatorScale(-1f))
        assertFalse(reducedMotionForAnimatorScale(0.5f))
        assertFalse(reducedMotionForAnimatorScale(1f))
    }
}
