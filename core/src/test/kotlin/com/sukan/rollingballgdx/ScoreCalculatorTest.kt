package com.sukan.rollingballgdx

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreCalculatorTest {

    @Test
    fun scoreAddsCoinsAndTime() {
        assertEquals(50, ScoreCalculator.calculate(coinsCollected = 3, timeLeftSeconds = 20))
    }

    @Test
    fun noCoinsAndNoTimeGivesZero() {
        assertEquals(0, ScoreCalculator.calculate(coinsCollected = 0, timeLeftSeconds = 0))
    }
}
