package com.sukan.rollingballgdx.android

import android.os.Bundle

import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.sukan.rollingballgdx.RollingBallGame

/** Launches the Android application. */
class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(RollingBallGame(), AndroidApplicationConfiguration().apply {
            useAccelerometer = true // Tilt input.
            useCompass = false
            useGyroscope = false
            useImmersiveMode = true // Recommended, but not required.
        })
    }
}
