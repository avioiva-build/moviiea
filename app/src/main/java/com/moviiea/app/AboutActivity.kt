package com.moviiea.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * About page — opens when the Moviiea name (top-left) or the AVIOIVA
 * badge (top-right) is tapped. Shows who makes the app, which version
 * is installed, the copyright note and the contact links.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Show the real version number of the installed app — always
        // matches the build, nothing hardcoded here.
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.textVersion).text = info.versionName ?: ""

        // Contact rows open the mail app / the browser.
        findViewById<View>(R.id.rowEmail).setOnClickListener {
            openExternal(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")))
        }
        findViewById<View>(R.id.rowWebsite).setOnClickListener {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EMAIL = "hex.leo.jara@gmail.com"
        private const val WEBSITE = "http://moviiea.blogspot.com"

        fun start(context: Context) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
    }
}
