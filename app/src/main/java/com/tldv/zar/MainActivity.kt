package com.tldv.zar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.tldv.zar.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastShakeTime = 0L

    private var result1 = 1
    private var result2 = 1
    private var isRolling = false
    private val handler = Handler(Looper.getMainLooper())

    private val history = mutableListOf<Pair<Int, Int>>()
    private val stats = mutableMapOf<Int, Int>()

    private var soundPool: SoundPool? = null
    private var tickSound = 0
    private var landSound = 0

    private var isDark = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        tickSound = soundPool!!.load(this, R.raw.dicetick, 1)
        landSound = soundPool!!.load(this, R.raw.diceland, 1)

        applyTheme()

        binding.rollButton.setOnClickListener { if (!isRolling) rollDice() }
        binding.diceContainer1.setOnClickListener { if (!isRolling) rollDice() }
        binding.diceContainer2.setOnClickListener { if (!isRolling) rollDice() }
        binding.themeToggle.setOnClickListener { toggleTheme() }
        binding.statsButton.setOnClickListener { showStats() }
        binding.statsClose.setOnClickListener { binding.statsCard.visibility = View.GONE }

        updateDice(binding.diceImage1, 1)
        updateDice(binding.diceImage2, 1)
    }

    override fun onResume() {
        super.onResume()
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val now = System.currentTimeMillis()

                if (now - lastShakeTime > 1000L) {
                    val delta = sqrt(
                        abs(x - lastX).let { it * it } +
                        abs(y - lastY).let { it * it } +
                        abs(z - lastZ).let { it * it }
                    )
                    if (delta > 12f && !isRolling) {
                        lastShakeTime = now
                        rollDice()
                    }
                }
                lastX = x; lastY = y; lastZ = z
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!isRolling) {
                    binding.diceContainer1.rotationX = event.values[0] * 3f
                    binding.diceContainer1.rotationY = event.values[1] * 3f
                    binding.diceContainer2.rotationX = event.values[0] * 3f
                    binding.diceContainer2.rotationY = event.values[1] * 3f
                }
            }
        }
    }

    private fun rollDice() {
        isRolling = true
        vibrate(80)

        binding.diceContainer1.startAnimation(AnimationUtils.loadAnimation(this, R.anim.dice_shake))
        binding.diceContainer2.startAnimation(AnimationUtils.loadAnimation(this, R.anim.dice_shake))
        binding.rollButton.isEnabled = false
        binding.sumText.text = ""

        var count = 0
        val runnable = object : Runnable {
            override fun run() {
                updateDice(binding.diceImage1, (1..6).random())
                updateDice(binding.diceImage2, (1..6).random())
                soundPool?.play(tickSound, 0.7f, 0.7f, 0, 0, 1f)
                count++

                if (count < 15) {
                    val delay = if (count < 8) 80L else 80L + (count - 8) * 25L
                    handler.postDelayed(this, delay)
                } else {
                    result1 = (1..6).random()
                    result2 = (1..6).random()
                    updateDice(binding.diceImage1, result1)
                    updateDice(binding.diceImage2, result2)

                    binding.sumText.text = (result1 + result2).toString()

                    history.add(0, Pair(result1, result2))
                    if (history.size > 8) history.removeAt(history.size - 1)
                    binding.historyText.text = history.drop(1)
                        .joinToString("  ") { "${it.first}+${it.second}" }

                    stats[result1] = (stats[result1] ?: 0) + 1
                    stats[result2] = (stats[result2] ?: 0) + 1

                    isRolling = false
                    binding.diceContainer1.clearAnimation()
                    binding.diceContainer2.clearAnimation()
                    binding.rollButton.isEnabled = true

                    binding.diceContainer1.startAnimation(
                        AnimationUtils.loadAnimation(this@MainActivity, R.anim.dice_result_pulse)
                    )
                    binding.diceContainer2.startAnimation(
                        AnimationUtils.loadAnimation(this@MainActivity, R.anim.dice_result_pulse)
                    )

                    soundPool?.play(landSound, 1f, 1f, 0, 0, 1f)
                    vibrate(200)
                }
            }
        }
        handler.post(runnable)
    }

    private fun updateDice(imageView: ImageView, value: Int) {
        imageView.setImageResource(when (value) {
            1 -> R.drawable.dice_1
            2 -> R.drawable.dice_2
            3 -> R.drawable.dice_3
            4 -> R.drawable.dice_4
            5 -> R.drawable.dice_5
            else -> R.drawable.dice_6
        })
    }

    private fun showStats() {
        if (stats.isEmpty()) return
        val total = stats.values.sum()
        val sb = StringBuilder()
        for (i in 1..6) {
            val count = stats[i] ?: 0
            val pct = if (total > 0) count * 100 / total else 0
            sb.appendLine("$i  →  $count kez  ($pct%)")
        }
        sb.append("\nToplam: $total atış")
        binding.statsContent.text = sb.toString().trim()
        binding.statsCard.visibility = View.VISIBLE
    }

    private fun toggleTheme() {
        isDark = !isDark
        applyTheme()
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val bg = if (isDark) getColor(R.color.bg_dark) else getColor(R.color.bg_light)
        val textPrimary = if (isDark) getColor(R.color.text_dark) else getColor(R.color.text_light)
        val textHint = if (isDark) getColor(R.color.hint_dark) else getColor(R.color.hint_light)
        val btnColor = if (isDark) getColor(R.color.gold) else getColor(R.color.gold_dark)
        val btnText = if (isDark) getColor(R.color.bg_dark) else getColor(R.color.white)

        binding.rootLayout.setBackgroundColor(bg)
        binding.titleText.setTextColor(if (isDark) getColor(R.color.gold) else getColor(R.color.gold_dark))
        binding.sumText.setTextColor(textPrimary)
        binding.historyText.setTextColor(textHint)
        binding.rollButton.setBackgroundColor(btnColor)
        binding.rollButton.setTextColor(btnText)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        if (!isDark) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(ms)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
