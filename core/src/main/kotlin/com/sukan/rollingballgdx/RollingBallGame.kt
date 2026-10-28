package com.sukan.rollingballgdx

import com.badlogic.gdx.Application
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.CharArray
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import ktx.app.clearScreen
import ktx.assets.disposeSafely

enum class GameState { PLAYING, WON, LOST }

/** Per-coin state. The instance is created once; only its transform and these fields change. */
private class Coin(val instance: ModelInstance) {
    var x = 0f
    var z = 0f
    var collected = false

    /** 0..1 progress of the shrink animation after collection. */
    var collectProgress = 0f
}

/** Rolling ball game: tilt the board to collect all coins before the timer runs out. */
class RollingBallGame : ApplicationAdapter() {

    private companion object {
        const val LOG_TAG = "RollingBallGdx"

        // Physics (same values as the OpenGL ES version).
        const val ACCELERATION_STRENGTH = 1.5f
        const val FRICTION = 0.8f
        const val BOUNCE = 0.5f
        const val BALL_RADIUS = 0.5f
        const val BOARD_HALF_SIZE = 5f
        const val MAX_DELTA = 0.05f
        const val BALL_START_X = 0f
        const val BALL_START_Z = 0f

        // Input.
        const val TILT_SMOOTHING = 0.15f

        /** Tilt (m/s^2) produced by an arrow key on desktop; roughly a 30 degree device tilt. */
        const val DESKTOP_KEY_TILT = 5f

        // Board.
        const val BOARD_SIZE = 10f
        const val BOARD_THICKNESS = 0.2f
        const val BOARD_CHECKS = 10
        const val BOARD_CHECK_PIXELS = 32

        // Ball.
        const val SPHERE_DIVISIONS_U = 24
        const val SPHERE_DIVISIONS_V = 16
        const val BALL_CHECKS_U = 8
        const val BALL_CHECKS_V = 4
        const val BALL_CHECK_PIXELS = 16

        // Coins.
        const val COIN_COUNT = 10
        const val COIN_RADIUS = 0.4f
        const val COIN_THICKNESS = 0.1f
        const val COIN_DIVISIONS = 24
        const val COIN_HEIGHT = 0.6f
        const val COIN_SPIN_DEGREES_PER_SECOND = 90f
        const val COIN_SHININESS = 32f
        const val COIN_COLLECT_DURATION = 0.25f

        /** Distance kept between a coin's center and the board edge. */
        const val COIN_EDGE_MARGIN = 1f
        const val COIN_MIN_DISTANCE_FROM_BALL = 1.5f
        const val COIN_MIN_DISTANCE_BETWEEN = 1f
        const val COIN_PLACEMENT_MAX_ATTEMPTS = 1000
        const val COLLECT_DISTANCE = BALL_RADIUS + COIN_RADIUS

        // Game rules.
        const val GAME_DURATION_SECONDS = 60f

        // Camera.
        const val CAMERA_FOV = 45f
        const val CAMERA_NEAR = 0.1f
        const val CAMERA_FAR = 100f
        const val CAMERA_HEIGHT = 9f
        const val CAMERA_DISTANCE = 9f

        // UI.
        const val UI_MARGIN_DP = 12f
        const val MESSAGE_SCALE = 1.4f
        const val WIN_MESSAGE = "You win! Tap to restart"
        const val LOSE_MESSAGE = "Time's up! Tap to restart"

        const val NANOS_PER_SECOND = 1_000_000_000L
    }

    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var camera: PerspectiveCamera

    private lateinit var boardTexture: Texture
    private lateinit var ballTexture: Texture
    private lateinit var boardModel: Model
    private lateinit var ballModel: Model
    private lateinit var coinModel: Model
    private lateinit var boardInstance: ModelInstance
    private lateinit var ballInstance: ModelInstance
    private lateinit var coins: Array<Coin>

    private lateinit var stage: Stage
    private lateinit var font: BitmapFont
    private lateinit var hudTable: Table
    private lateinit var scoreLabel: Label
    private lateinit var timerLabel: Label
    private lateinit var messageLabel: Label

    /** Reused for every label text so updates don't create Strings. */
    private val labelText = CharArray(32)
    private var uiScale = 1f

    private var isDesktop = false

    // Ball state. x/z of position are used; y is fixed at BALL_RADIUS.
    private val position = Vector3()
    private val velocity = Vector3()
    private var rollAngleX = 0f
    private var rollAngleZ = 0f

    // Low-pass filtered tilt, in board axes (x = right, z = towards the viewer).
    private var smoothTiltX = 0f
    private var smoothTiltZ = 0f

    // Game state.
    private var state = GameState.PLAYING
    private var score = 0
    private var timeLeft = GAME_DURATION_SECONDS
    private var displayedSeconds = -1
    private var coinSpinDegrees = 0f

    // Measurement.
    private var logWindowStart = 0L
    private var workNanosSum = 0L
    private var workFrames = 0

    override fun create() {

        val test: String = "hello"

        isDesktop = Gdx.app.type == Application.ApplicationType.Desktop

        modelBatch = ModelBatch()

        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
            // Pointing down and away from the camera, i.e. coming from above and slightly in front.
            add(DirectionalLight().set(0.8f, 0.8f, 0.8f, 0f, -1f, -0.5f))
        }

        camera = PerspectiveCamera(
            CAMERA_FOV,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat()
        ).apply {
            near = CAMERA_NEAR
            far = CAMERA_FAR
        }

        boardTexture = createCheckerTexture(
            BOARD_CHECKS, BOARD_CHECKS, BOARD_CHECK_PIXELS,
            Color(0.20f, 0.55f, 0.20f, 1f), Color(0.12f, 0.38f, 0.12f, 1f),
        )
        ballTexture = createCheckerTexture(
            BALL_CHECKS_U, BALL_CHECKS_V, BALL_CHECK_PIXELS,
            Color(0.85f, 0.10f, 0.10f, 1f), Color.WHITE,
        )

        val modelBuilder = ModelBuilder()
        val attributes = (Usage.Position or Usage.Normal or Usage.TextureCoordinates).toLong()
        boardModel = modelBuilder.createBox(
            BOARD_SIZE, BOARD_THICKNESS, BOARD_SIZE,
            Material(TextureAttribute.createDiffuse(boardTexture)), attributes,
        )
        ballModel = modelBuilder.createSphere(
            BALL_RADIUS * 2f, BALL_RADIUS * 2f, BALL_RADIUS * 2f,
            SPHERE_DIVISIONS_U, SPHERE_DIVISIONS_V,
            Material(TextureAttribute.createDiffuse(ballTexture)), attributes,
        )
        // Cylinder axis is Y; each instance is turned 90 degrees around X so the faces point sideways.
        coinModel = modelBuilder.createCylinder(
            COIN_RADIUS * 2f, COIN_THICKNESS, COIN_RADIUS * 2f, COIN_DIVISIONS,
            Material(
                ColorAttribute.createDiffuse(1f, 0.78f, 0.2f, 1f),
                ColorAttribute.createSpecular(1f, 1f, 0.85f, 1f),
                FloatAttribute.createShininess(COIN_SHININESS),
            ),
            (Usage.Position or Usage.Normal).toLong(),
        )

        // createBox is centered on its own origin; shift it down so the top surface is at y = 0.
        boardInstance = ModelInstance(boardModel).apply {
            transform.setToTranslation(
                0f,
                -BOARD_THICKNESS / 2f,
                0f
            )
        }
        ballInstance = ModelInstance(ballModel)
        coins = Array(COIN_COUNT) { Coin(ModelInstance(coinModel)) }

        createUi()
        restartGame()

        logWindowStart = TimeUtils.nanoTime()
    }

    private fun createUi() {
        stage = Stage(ScreenViewport())
        uiScale = maxOf(1f, Gdx.graphics.density)

        font = BitmapFont().apply {
            // The default font is a small bitmap font; smooth it since it is scaled up on phones.
            region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        val style = Label.LabelStyle(font, Color.WHITE)
        scoreLabel = Label("", style).apply { setFontScale(uiScale) }
        timerLabel = Label("", style).apply { setFontScale(uiScale) }
        messageLabel = Label("", style).apply {
            setFontScale(uiScale * MESSAGE_SCALE)
            isVisible = false
        }

        hudTable = Table().apply {
            setFillParent(true)
            touchable = Touchable.disabled
            top()
            add(scoreLabel).expandX().left()
            add(timerLabel).right()
        }
        val messageTable = Table().apply {
            setFillParent(true)
            touchable = Touchable.disabled
            add(messageLabel)
        }
        stage.addActor(hudTable)
        stage.addActor(messageTable)
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()

        stage.viewport.update(width, height, true)
        // Keep the HUD clear of the status bar / display cutout (insets are in pixels, as is ScreenViewport).
        val margin = UI_MARGIN_DP * uiScale
        hudTable.pad(
            Gdx.graphics.safeInsetTop + margin,
            Gdx.graphics.safeInsetLeft + margin,
            Gdx.graphics.safeInsetBottom + margin,
            Gdx.graphics.safeInsetRight + margin,
        )
        hudTable.invalidate()
    }

    override fun render() {
        val workStart = TimeUtils.nanoTime()
        val delta = minOf(Gdx.graphics.deltaTime, MAX_DELTA)

        if (state != GameState.PLAYING && isRestartRequested()) restartGame()

        updateTilt()
        if (state == GameState.PLAYING) {
            updatePhysics(delta)
            checkCoinCollection()
            updateTimer(delta)
        }
        updateBallTransform()
        updateCoins(delta)
        updateCamera()

        clearScreen(0.1f, 0.1f, 0.15f)
        modelBatch.begin(camera)
        modelBatch.render(boardInstance, environment)
        modelBatch.render(ballInstance, environment)
        for (i in 0 until COIN_COUNT) {
            val coin = coins[i]
            if (!coin.collected || coin.collectProgress < 1f) modelBatch.render(
                coin.instance,
                environment
            )
        }
        modelBatch.end()

        stage.act(delta)
        stage.draw()

        val workEnd = TimeUtils.nanoTime()
        recordFrame(workEnd - workStart, workEnd)
    }

    private fun isRestartRequested(): Boolean =
        Gdx.input.justTouched() || (isDesktop && Gdx.input.isKeyJustPressed(Input.Keys.R))

    private fun restartGame() {
        position.set(BALL_START_X, 0f, BALL_START_Z)
        velocity.setZero()
        rollAngleX = 0f
        rollAngleZ = 0f

        placeCoins()

        score = 0
        timeLeft = GAME_DURATION_SECONDS
        displayedSeconds = -1
        state = GameState.PLAYING

        updateScoreLabel()
        updateTimerLabel()
        updateMessageLabel()
    }

    /** Random coin positions with an edge margin, away from the ball start and from each other. */
    private fun placeCoins() {
        val range = BOARD_HALF_SIZE - COIN_EDGE_MARGIN
        val minBallDist2 = COIN_MIN_DISTANCE_FROM_BALL * COIN_MIN_DISTANCE_FROM_BALL
        val minCoinDist2 = COIN_MIN_DISTANCE_BETWEEN * COIN_MIN_DISTANCE_BETWEEN
        for (i in 0 until COIN_COUNT) {
            var x = 0f
            var z = 0f
            // The board has plenty of room for 10 coins; the attempt cap only guards against an endless loop.
            for (attempt in 0 until COIN_PLACEMENT_MAX_ATTEMPTS) {
                x = MathUtils.random(-range, range)
                z = MathUtils.random(-range, range)
                if (distance2(x, z, BALL_START_X, BALL_START_Z) < minBallDist2) continue
                var tooClose = false
                for (j in 0 until i) {
                    if (distance2(x, z, coins[j].x, coins[j].z) < minCoinDist2) {
                        tooClose = true
                        break
                    }
                }
                if (!tooClose) break
            }
            val coin = coins[i]
            coin.x = x
            coin.z = z
            coin.collected = false
            coin.collectProgress = 0f
        }
    }

    private fun distance2(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x1 - x2
        val dz = z1 - z2
        return dx * dx + dz * dz
    }

    private fun updateTilt() {
        var rawX: Float
        var rawZ: Float
        if (isDesktop) {
            rawX = 0f
            rawZ = 0f
            if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) rawX += DESKTOP_KEY_TILT
            if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) rawX -= DESKTOP_KEY_TILT
            if (Gdx.input.isKeyPressed(Input.Keys.UP)) rawZ -= DESKTOP_KEY_TILT
            if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) rawZ += DESKTOP_KEY_TILT
        } else {
            // Portrait, same sign convention as TYPE_GRAVITY: tilting right makes x negative,
            // tilting the top edge away (forward) makes y negative. Forward on the board is -z.
            rawX = -Gdx.input.accelerometerX
            rawZ = Gdx.input.accelerometerY
        }
        smoothTiltX += (rawX - smoothTiltX) * TILT_SMOOTHING
        smoothTiltZ += (rawZ - smoothTiltZ) * TILT_SMOOTHING
    }

    private fun updatePhysics(delta: Float) {
        velocity.x += smoothTiltX * ACCELERATION_STRENGTH * delta
        velocity.z += smoothTiltZ * ACCELERATION_STRENGTH * delta

        val damping = 1f - FRICTION * delta
        velocity.x *= damping
        velocity.z *= damping

        position.x += velocity.x * delta
        position.z += velocity.z * delta

        val limit = BOARD_HALF_SIZE - BALL_RADIUS
        if (position.x > limit) {
            position.x = limit
            velocity.x = -velocity.x * BOUNCE
        } else if (position.x < -limit) {
            position.x = -limit
            velocity.x = -velocity.x * BOUNCE
        }
        if (position.z > limit) {
            position.z = limit
            velocity.z = -velocity.z * BOUNCE
        } else if (position.z < -limit) {
            position.z = -limit
            velocity.z = -velocity.z * BOUNCE
        }

        rollAngleX += (velocity.z * delta) / BALL_RADIUS
        rollAngleZ -= (velocity.x * delta) / BALL_RADIUS
    }

    private fun checkCoinCollection() {
        val collectDist2 = COLLECT_DISTANCE * COLLECT_DISTANCE
        for (i in 0 until COIN_COUNT) {
            val coin = coins[i]
            if (coin.collected) continue
            if (distance2(position.x, position.z, coin.x, coin.z) <= collectDist2) {
                coin.collected = true
                score++
                updateScoreLabel()
            }
        }
        if (score == COIN_COUNT) setState(GameState.WON)
    }

    private fun updateTimer(delta: Float) {
        if (state != GameState.PLAYING) return
        timeLeft -= delta
        if (timeLeft <= 0f) {
            timeLeft = 0f
            setState(GameState.LOST)
        }
        updateTimerLabel()
    }

    private fun setState(newState: GameState) {
        if (state == newState) return
        state = newState
        updateMessageLabel()
    }

    private fun updateBallTransform() {
        ballInstance.transform
            .setToTranslation(position.x, BALL_RADIUS, position.z)
            .rotateRad(Vector3.X, rollAngleX)
            .rotateRad(Vector3.Z, rollAngleZ)
    }

    /** Spin and shrink animations run in every state, so they keep going after a win or loss. */
    private fun updateCoins(delta: Float) {
        coinSpinDegrees = (coinSpinDegrees + COIN_SPIN_DEGREES_PER_SECOND * delta) % 360f
        for (i in 0 until COIN_COUNT) {
            val coin = coins[i]
            if (coin.collected && coin.collectProgress < 1f) {
                coin.collectProgress =
                    minOf(1f, coin.collectProgress + delta / COIN_COLLECT_DURATION)
            }
            val scale = 1f - coin.collectProgress
            coin.instance.transform
                .setToTranslation(coin.x, COIN_HEIGHT, coin.z)
                .rotate(Vector3.Y, coinSpinDegrees)
                .rotate(Vector3.X, 90f)
                .scale(scale, scale, scale)
        }
    }

    private fun updateCamera() {
        camera.position.set(position.x, CAMERA_HEIGHT, position.z + CAMERA_DISTANCE)
        camera.up.set(Vector3.Y)
        camera.lookAt(position.x, 0f, position.z)
        camera.update()
    }

    private fun updateScoreLabel() {
        labelText.clear()
        labelText.append("Score: ").append(score).append('/').append(COIN_COUNT)
        scoreLabel.setText(labelText)
    }

    /** Rewrites the m:ss text only when the whole-second value changes. */
    private fun updateTimerLabel() {
        val seconds = MathUtils.ceil(timeLeft)
        if (seconds == displayedSeconds) return
        displayedSeconds = seconds
        labelText.clear()
        labelText.append(seconds / 60).append(':').append(seconds % 60, 2, '0')
        timerLabel.setText(labelText)
    }

    private fun updateMessageLabel() {
        when (state) {
            GameState.PLAYING -> messageLabel.isVisible = false
            GameState.WON -> {
                messageLabel.setText(WIN_MESSAGE)
                messageLabel.isVisible = true
            }

            GameState.LOST -> {
                messageLabel.setText(LOSE_MESSAGE)
                messageLabel.isVisible = true
            }
        }
    }

    private fun recordFrame(workNanos: Long, now: Long) {
        workNanosSum += workNanos
        workFrames++
        if (now - logWindowStart >= NANOS_PER_SECOND) {
            val avgWorkMs = workNanosSum / workFrames / 1_000_000.0
            Gdx.app.log(
                LOG_TAG,
                "FPS: ${Gdx.graphics.framesPerSecond}, avg work: ${"%.3f".format(avgWorkMs)} ms"
            )
            logWindowStart = now
            workNanosSum = 0L
            workFrames = 0
        }
    }

    /** Builds a [cellsX] x [cellsY] checkerboard texture at runtime. */
    private fun createCheckerTexture(
        cellsX: Int,
        cellsY: Int,
        cellPixels: Int,
        colorA: Color,
        colorB: Color
    ): Texture {
        val pixmap = Pixmap(cellsX * cellPixels, cellsY * cellPixels, Pixmap.Format.RGBA8888)
        for (cy in 0 until cellsY) {
            for (cx in 0 until cellsX) {
                pixmap.setColor(if ((cx + cy) % 2 == 0) colorA else colorB)
                pixmap.fillRectangle(cx * cellPixels, cy * cellPixels, cellPixels, cellPixels)
            }
        }
        val texture = Texture(pixmap)
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        pixmap.dispose()
        return texture
    }

    override fun dispose() {
        boardModel.disposeSafely()
        ballModel.disposeSafely()
        coinModel.disposeSafely()
        boardTexture.disposeSafely()
        ballTexture.disposeSafely()
        modelBatch.disposeSafely()
        stage.disposeSafely()
        font.disposeSafely()
    }
}
