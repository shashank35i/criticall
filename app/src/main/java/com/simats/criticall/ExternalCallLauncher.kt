package com.simats.criticall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import java.util.Locale

object ExternalCallLauncher {
    fun buildIntent(
        ctx: Context,
        consultType: String,
        room: String?,
        server: String?,
        phone: String?,
        explicitLink: String? = null
    ): Intent? {
        val link = explicitLink?.trim().orEmpty()
        if (link.isNotBlank()) {
            return buildViewIntent(ctx, link)
        }

        val t = consultType.trim().uppercase(Locale.US)
        return if (t == "VIDEO") {
            val r = room?.trim().orEmpty()
            if (r.isBlank()) return null
            val url = buildVideoUrl(server, r)
            buildViewIntent(ctx, url)
        } else {
            val p = phone?.trim().orEmpty()
            if (p.isBlank()) return null
            buildDialIntent(ctx, p)
        }
    }

    fun start(activity: Activity, intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildViewIntent(ctx: Context, raw: String): Intent? {
        val uri = Uri.parse(raw)
        val itn = Intent(Intent.ACTION_VIEW, uri)
        return if (itn.resolveActivity(ctx.packageManager) != null) itn else null
    }

    private fun buildDialIntent(ctx: Context, phone: String): Intent? {
        val tel = if (phone.startsWith("tel:", true)) phone else "tel:$phone"
        val itn = Intent(Intent.ACTION_DIAL, Uri.parse(tel))
        return if (itn.resolveActivity(ctx.packageManager) != null) itn else null
    }

    private fun buildVideoUrl(server: String?, room: String): String {
        val s0 = server?.trim().orEmpty().trimEnd('/')
        val base = if (s0.isBlank()) ApiConfig.JITSI_BASE_URL.trimEnd('/') else s0
        val normalized = when {
            base.startsWith("http://", true) ->
                "https://" + base.removePrefix("http://").trimStart('/')
            base.startsWith("https://", true) -> base
            else -> "https://$base"
        }.trimEnd('/')

        val safeRoom = room.trim().trimStart('/')
        return if (TextUtils.isEmpty(safeRoom)) normalized else "$normalized/$safeRoom"
    }
}
