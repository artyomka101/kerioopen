package com.openkerio.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.openkerio.vpn.data.AppSettings
import com.openkerio.vpn.data.SessionLog

class SessionLogActivity : AppCompatActivity() {
    private lateinit var logText: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_log)
        logText = findViewById(R.id.logText)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.exportLogButton).setOnClickListener {
            runCatching {
                startActivity(
                    Intent.createChooser(
                        SessionLog.exportShareIntent(this),
                        getString(R.string.settings_export_log),
                    ),
                )
            }.onFailure {
                Toast.makeText(this, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.clearLogButton).setOnClickListener {
            SessionLog.clear()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val snap = SessionLog.snapshot()
        logText.text = if (snap.isEmpty()) {
            getString(R.string.settings_log_empty)
        } else {
            snap.joinToString("\n")
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SessionLogActivity::class.java))
        }
    }
}
