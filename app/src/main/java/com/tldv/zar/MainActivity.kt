package com.tldv.zar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
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
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.tldv.zar.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Calendar

// ────────────────────────────────────────────────────────────────────────────
// Accent theme enum
// ────────────────────────────────────────────────────────────────────────────
enum class AccentTheme(val primary: Int, val dark: Int) {
    GOLD(Color.parseColor("#F5C842"), Color.parseColor("#C9A227")),
    BLUE(Color.parseColor("#2196F3"), Color.parseColor("#1565C0")),
    RED(Color.parseColor("#F44336"),  Color.parseColor("#B71C1C")),
    GREEN(Color.parseColor("#4CAF50"), Color.parseColor("#1B5E20"))
}

// ────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private lateinit var prefs: SharedPreferences

    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private var lastShakeTime = 0L

    private var result1 = 1; private var result2 = 1
    private var isRolling = false
    private val handler = Handler(Looper.getMainLooper())

    // History stored as list of strings "d1,d2" → persistent via prefs
    private val history = mutableListOf<Pair<Int,Int>>()
    private val stats   = mutableMapOf<Int,Int>()

    private var soundPool: SoundPool? = null
    private var tickSound = 0; private var landSound = 0

    // Settings state
    private var isDark       = true
    private var accentTheme  = AccentTheme.GOLD
    private var volume       = 0.7f        // 0..1
    private var speedLevel   = 2           // 0..4 → maps to delay multiplier
    private var nightTimerEnabled = false
    private var nightHour    = 22
    private var nightMinute  = 0

    // Confetti
    private val confettiColors = intArrayOf(
        Color.parseColor("#F5C842"), Color.parseColor("#2196F3"),
        Color.parseColor("#F44336"), Color.parseColor("#4CAF50"),
        Color.parseColor("#E91E63"), Color.parseColor("#9C27B0")
    )

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("zar_prefs", Context.MODE_PRIVATE)
        loadPrefs()

        sensorManager  = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        tickSound  = soundPool!!.load(this, R.raw.dicetick, 1)
        landSound  = soundPool!!.load(this, R.raw.diceland, 1)

        applyTheme()

        binding.rollButton.setOnClickListener        { if (!isRolling) rollDice() }
        binding.diceContainer1.setOnClickListener    { if (!isRolling) rollDice() }
        binding.diceContainer2.setOnClickListener    { if (!isRolling) rollDice() }
        binding.statsButton.setOnClickListener       { showStats() }
        binding.statsClose.setOnClickListener        { binding.statsCard.visibility = View.GONE }
        binding.settingsButton.setOnClickListener    { showSettings() }
        binding.settingsClose.setOnClickListener     { hideSettings() }
        binding.shareButton.setOnClickListener       { shareResult() }

        // Dark mode switch in settings
        binding.darkModeSwitch.isChecked = isDark
        binding.darkModeSwitch.setOnCheckedChangeListener { _, checked ->
            isDark = checked
            applyTheme()
            prefs.edit().putBoolean("dark", isDark).apply()
        }

        // Volume seekbar (0-10 → 0.0-1.0)
        binding.volumeSeekBar.progress = (volume * 10).toInt()
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                volume = p / 10f
                prefs.edit().putFloat("volume", volume).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Speed seekbar (0-4)
        binding.speedSeekBar.progress = speedLevel
        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                speedLevel = p
                prefs.edit().putInt("speed", speedLevel).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Night timer switch
        binding.nightTimerSwitch.isChecked = nightTimerEnabled
        binding.nightTimerRow.visibility = if (nightTimerEnabled) View.VISIBLE else View.GONE
        binding.nightTimerSwitch.setOnCheckedChangeListener { _, checked ->
            nightTimerEnabled = checked
            binding.nightTimerRow.visibility = if (checked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("night_timer", checked).apply()
            if (checked) scheduleNightModeAlarm() else cancelNightModeAlarm()
        }
        updateNightTimerLabel()
        binding.nightTimePickerBtn.setOnClickListener { pickNightTime() }

        // Color theme buttons
        binding.themeGold.setOnClickListener  { setAccent(AccentTheme.GOLD) }
        binding.themeBlue.setOnClickListener  { setAccent(AccentTheme.BLUE) }
        binding.themeRed.setOnClickListener   { setAccent(AccentTheme.RED)  }
        binding.themeGreen.setOnClickListener { setAccent(AccentTheme.GREEN) }

        updateDice(binding.diceImage1, 1)
        updateDice(binding.diceImage2, 1)
        renderHistory()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prefs
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadPrefs() {
        isDark             = prefs.getBoolean("dark", true)
        volume             = prefs.getFloat("volume", 0.7f)
        speedLevel         = prefs.getInt("speed", 2)
        nightTimerEnabled  = prefs.getBoolean("night_timer", false)
        nightHour          = prefs.getInt("night_hour", 22)
        nightMinute        = prefs.getInt("night_minute", 0)
        accentTheme        = AccentTheme.valueOf(prefs.getString("accent", "GOLD") ?: "GOLD")

        // Restore history
        val raw = prefs.getString("history", "") ?: ""
        if (raw.isNotEmpty()) {
            raw.split("|").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    val a = parts[0].toIntOrNull() ?: return@forEach
                    val b = parts[1].toIntOrNull() ?: return@forEach
                    history.add(Pair(a, b))
                }
            }
        }
        // Restore stats
        val statsRaw = prefs.getString("stats", "") ?: ""
        if (statsRaw.isNotEmpty()) {
            statsRaw.split("|").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    val k = parts[0].toIntOrNull() ?: return@forEach
                    val v = parts[1].toIntOrNull() ?: return@forEach
                    stats[k] = v
                }
            }
        }
    }

    private fun saveHistory() {
        val raw = history.joinToString("|") { "${it.first},${it.second}" }
        prefs.edit().putString("history", raw).apply()
    }

    private fun saveStats() {
        val raw = stats.entries.joinToString("|") { "${it.key},${it.value}" }
        prefs.edit().putString("stats", raw).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sensor
    // ─────────────────────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        gyroscope?.let    { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ─────────────────────────────────────────────────────────────────────────
    // Roll
    // ─────────────────────────────────────────────────────────────────────────
    // speedLevel 0=slow … 4=fast
    // base tick delay (ms): 0→120, 1→100, 2→80, 3→60, 4→40
    private fun baseDelay() = when(speedLevel) { 0->120L; 1->100L; 2->80L; 3->60L; else->40L }
    // slowdown factor: how many extra ms added per tick past 8
    private fun slowFactor() = when(speedLevel) { 0->40L; 1->30L; 2->25L; 3->18L; else->12L }

    private fun rollDice() {
        isRolling = true
        vibrate(80)
        binding.diceContainer1.startAnimation(AnimationUtils.loadAnimation(this, R.anim.dice_shake))
        binding.diceContainer2.startAnimation(AnimationUtils.loadAnimation(this, R.anim.dice_shake))
        binding.rollButton.isEnabled = false
        binding.sumText.text = ""
        binding.shareButton.visibility = View.GONE

        var count = 0
        val runnable = object : Runnable {
            override fun run() {
                updateDice(binding.diceImage1, (1..6).random())
                updateDice(binding.diceImage2, (1..6).random())
                soundPool?.play(tickSound, volume, volume, 0, 0, 1f)
                count++

                if (count < 15) {
                    val delay = if (count < 8) baseDelay() else baseDelay() + (count - 8) * slowFactor()
                    handler.postDelayed(this, delay)
                } else {
                    result1 = (1..6).random()
                    result2 = (1..6).random()
                    updateDice(binding.diceImage1, result1)
                    updateDice(binding.diceImage2, result2)

                    val sum = result1 + result2
                    binding.sumText.text = sum.toString()

                    // History
                    history.add(0, Pair(result1, result2))
                    if (history.size > 20) history.removeAt(history.size - 1)
                    renderHistory()
                    saveHistory()

                    // Stats
                    stats[result1] = (stats[result1] ?: 0) + 1
                    stats[result2] = (stats[result2] ?: 0) + 1
                    saveStats()

                    isRolling = false
                    binding.diceContainer1.clearAnimation()
                    binding.diceContainer2.clearAnimation()
                    binding.rollButton.isEnabled = true

                    binding.diceContainer1.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.dice_result_pulse))
                    binding.diceContainer2.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.dice_result_pulse))

                    soundPool?.play(landSound, volume, volume, 0, 0, 1f)
                    vibrate(200)

                    binding.shareButton.visibility = View.VISIBLE

                    // Confetti on double-6
                    if (result1 == 6 && result2 == 6) {
                        launchConfetti()
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun renderHistory() {
        binding.historyText.text = history.drop(1)
            .take(7)
            .joinToString("  ") { "🎲${it.first}+${it.second}" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confetti
    // ─────────────────────────────────────────────────────────────────────────
    private fun launchConfetti() {
        val container = binding.confettiContainer
        val w = container.width.takeIf { it > 0 } ?: 800
        val h = container.height.takeIf { it > 0 } ?: 1600

        repeat(60) { i ->
            val particle = View(this)
            val size = (8..20).random()
            val params = FrameLayout.LayoutParams(
                dpToPx(size), dpToPx(size)
            )
            particle.layoutParams = params
            particle.setBackgroundColor(confettiColors[i % confettiColors.size])
            particle.rotation = (0..360).random().toFloat()

            val startX = (0..w).random().toFloat()
            particle.x = startX
            particle.y = -dpToPx(20).toFloat()
            container.addView(particle)

            val duration = (1200L..2200L).random()
            val endX = startX + (-200..200).random()
            val endY = h.toFloat() + dpToPx(20)

            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = duration
            animator.startDelay = (i * 30L)
            animator.addUpdateListener { va ->
                val t = va.animatedFraction
                particle.x = startX + (endX - startX) * t
                particle.y = -dpToPx(20).toFloat() + (endY + dpToPx(20)) * t
                particle.alpha = if (t > 0.8f) 1f - (t - 0.8f) * 5f else 1f
                particle.rotation += 4f
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.removeView(particle)
                }
            })
            animator.start()
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────────────────
    // Share
    // ─────────────────────────────────────────────────────────────────────────
    private fun shareResult() {
        val text = getString(R.string.share_text, result1, result2, result1 + result2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    private fun showStats() {
        if (stats.isEmpty()) return
        val total = stats.values.sum()
        val sb = StringBuilder()
        for (i in 1..6) {
            val count = stats[i] ?: 0
            val pct = if (total > 0) count * 100 / total else 0
            val bar = "█".repeat(pct / 5)
            sb.appendLine("$i  →  $count kez  ($pct%)  $bar")
        }
        sb.append("\nToplam: $total atış")
        binding.statsContent.text = sb.toString().trim()
        binding.statsCard.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings panel
    // ─────────────────────────────────────────────────────────────────────────
    private fun showSettings() {
        binding.darkModeSwitch.isChecked = isDark
        binding.volumeSeekBar.progress  = (volume * 10).toInt()
        binding.speedSeekBar.progress   = speedLevel
        binding.nightTimerSwitch.isChecked = nightTimerEnabled
        binding.nightTimerRow.visibility   = if (nightTimerEnabled) View.VISIBLE else View.GONE
        updateNightTimerLabel()
        binding.settingsCard.visibility = View.VISIBLE
    }

    private fun hideSettings() {
        binding.settingsCard.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Night mode timer
    // ─────────────────────────────────────────────────────────────────────────
    private fun pickNightTime() {
        TimePickerDialog(this, { _, h, m ->
            nightHour = h; nightMinute = m
            prefs.edit().putInt("night_hour", h).putInt("night_minute", m).apply()
            updateNightTimerLabel()
            if (nightTimerEnabled) scheduleNightModeAlarm()
        }, nightHour, nightMinute, true).show()
    }

    private fun updateNightTimerLabel() {
        binding.nightTimePickerBtn.text = "%02d:%02d".format(nightHour, nightMinute)
    }

    private fun nightModeIntent(): PendingIntent {
        val intent = Intent(this, NightModeReceiver::class.java)
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleNightModeAlarm() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, nightHour)
            set(Calendar.MINUTE, nightMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, nightModeIntent())
        } else {
            am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, nightModeIntent())
        }
    }

    private fun cancelNightModeAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(nightModeIntent())
    }

    // Called by NightModeReceiver to trigger dark mode
    fun enableDarkMode() {
        isDark = true
        prefs.edit().putBoolean("dark", true).apply()
        applyTheme()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Theme
    // ─────────────────────────────────────────────────────────────────────────
    private fun setAccent(theme: AccentTheme) {
        accentTheme = theme
        prefs.edit().putString("accent", theme.name).apply()
        applyTheme()
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val bg        = if (isDark) getColor(R.color.bg_dark)   else getColor(R.color.bg_light)
        val textPrimary = if (isDark) getColor(R.color.text_dark) else getColor(R.color.text_light)
        val textHint  = if (isDark) getColor(R.color.hint_dark) else getColor(R.color.hint_light)
        val btnText   = if (isDark) getColor(R.color.bg_dark)   else getColor(R.color.white)
        val accent    = accentTheme.primary
        val accentDark = accentTheme.dark

        binding.rootLayout.setBackgroundColor(bg)
        binding.titleText.setTextColor(accent)
        binding.sumText.setTextColor(textPrimary)
        binding.historyText.setTextColor(textHint)
        binding.rollButton.setBackgroundColor(accent)
        binding.rollButton.setTextColor(btnText)
        binding.themeModeLabel.text = if (isDark) "🌙" else "☀️"

        window.statusBarColor     = bg
        window.navigationBarColor = bg

        if (!isDark) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dice faces
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateDice(imageView: ImageView, value: Int) {
        imageView.setImageResource(when (value) {
            1 -> R.drawable.dice_1; 2 -> R.drawable.dice_2; 3 -> R.drawable.dice_3
            4 -> R.drawable.dice_4; 5 -> R.drawable.dice_5; else -> R.drawable.dice_6
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vibration
    // ─────────────────────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}
