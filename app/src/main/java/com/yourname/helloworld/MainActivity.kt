package com.yourname.helloworld

import android.os.Bundle
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            startActivity(Intent(this, GitHubPushActivity::class.java))
        }

        findViewById<TextView>(R.id.commitText).text = "Latest commit: ${BuildConfig.LATEST_COMMIT_MSG}"

        // Traveling rocket animation 🚀
        val rocket = findViewById<TextView>(R.id.rocket)
        val parent = rocket.parent as? android.view.View
        rocket.post {
            val parentWidth = parent?.width ?: return@post
            val startX = -rocket.width.toFloat()
            val endX = (parentWidth + rocket.width).toFloat()

            rocket.translationX = startX
            ObjectAnimator.ofFloat(rocket, "translationX", startX, endX).apply {
                duration = 2500
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }
}
