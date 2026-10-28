package com.sukan.rollingballgdx

object ScoreCalculator {
    fun calculate(coinsCollected: Int, timeLeftSeconds: Int): Int {
        return coinsCollected * 10 + timeLeftSeconds
    }
}
