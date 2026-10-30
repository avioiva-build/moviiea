package com.moviiea.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Builds the exact same video links the Moviiea website builds
 * (VidAPI / VidSrc / 2Embed, keyed by TMDB id) and opens them in Chrome.
 *
 * v2.7: playback goes back to the PROVEN external path — a Chrome
 * Custom Tab styled like the Moviiea app (navy toolbar, slide
 * animations). The video servers keep blocking WebView engines with ad
 * pop-ups and empty pages, so the in-app player screen is parked for
 * now: PlayerActivity (the v2.6 code) stays in the project, but nothing
 * opens it. openInApp() below is the ready-made switch for the day we
 * revive the in-app player.
 *
 * The link is wrapped in the small player.html bridge page (hosted on
 * GitHub Pages) because the servers only serve video when they are
 * embedded inside a page — exactly like the website does it.
 */
object PlayerLauncher {

    /** The bridge page — must be reachable via GitHub Pages. */
    const val PLAYER_PAGE = "https://avioiva-build.github.io/moviiea/player.html"

    /** Flips to true after the app successfully checked the bridge exists. */
    var bridgeVerified = false

    data class Server(
        val name: String,
        val url: (id: Int, season: Int, episode: Int, isMovie: Boolean) -> String
    )

    /** Same three servers, same URL patterns, as on the website. */
    val SERVERS = listOf(
        Server("VidAPI") { id, s, e, isMovie ->
            if (isMovie) "https://vidapi.xyz/embed/movie/$id"
            else "https://vidapi.xyz/embed/tv/$id&s=$s&e=$e"
        },
        Server("VidSrc") { id, s, e, isMovie ->
            if (isMovie) "https://vidsrc.xyz/embed/movie/$id"
            else "https://vidsrc.xyz/embed/tv/$id/$s/$e"
        },
        Server("2Embed") { id, s, e, isMovie ->
            if (isMovie) "https://www.2embed.cc/embed/$id"
            else "https://www.2embed.cc/embedtv/$id&s=$s&e=$e"
        }
    )

    /** Build the embed link for the chosen server (mirrors the website). */
    fun embedUrl(serverIndex: Int, id: Int, season: Int, episode: Int, isMovie: Boolean): String {
        val safeIndex = serverIndex.coerceIn(0, SERVERS.size - 1)
        return SERVERS[safeIndex].url(id, season, episode, isMovie)
    }

    /** Wrap an embed link inside the bridge page. */
    fun bridgeUrl(embedUrl: String): String =
        Uri.parse(PLAYER_PAGE).buildUpon()
            .appendQueryParameter("u", embedUrl)
            .build()
            .toString()

    /**
     * PARKED (future in-app player): opens playback INSIDE the app via
     * PlayerActivity. The video servers currently block WebView engines,
     * so nothing calls this in v2.7 — kept ready for later work.
     */
    fun openInApp(context: Context, embedUrl: String, title: String) {
        PlayerActivity.start(context, embedUrl, title)
    }

    /** Open the bridge page in Chrome, with sensible fallbacks. */
    fun open(context: Context, embedUrl: String) {
        val url = bridgeUrl(embedUrl)

        // 1st choice: Chrome Custom Tab, styled like the Moviiea app.
        try {
            val scheme = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(0xFF16305C.toInt())      // Moviiea surface navy
                .setNavigationBarColor(0xFF091E49.toInt()) // Moviiea dark navy
                .build()
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(scheme)
                .setStartAnimations(context, R.anim.slide_in_right, R.anim.slide_out_left)
                .setExitAnimations(context, R.anim.slide_in_left, R.anim.slide_out_right)
                .build()
                .launchUrl(context, Uri.parse(url))
            return
        } catch (e: Exception) {
            // fall through to plain browser intents
        }

        // 2nd choice: Chrome explicitly
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
            )
            return
        } catch (e: ActivityNotFoundException) {
            // fall through
        }

        // 3rd choice: whatever browser is installed
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
