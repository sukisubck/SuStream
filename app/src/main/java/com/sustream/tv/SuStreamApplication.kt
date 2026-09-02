package com.sustream.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sustream.tv.core.di.AppContainer
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.domain.model.NotificationChannelId

private const val TAG = "App"

/**
 * Owns the application object graph.
 *
 * Dependency injection is manual: [container] is constructed once here and reaches composables
 * through `LocalAppContainer`. See docs/IMPLEMENTATION_PLAN.md section 2.2 for why Hilt is not used.
 *
 * `onCreate` deliberately does almost nothing. Every dependency in [AppContainer] is lazy, so the
 * database, the HTTP clients and the player are not built until something asks for them. On a Fire
 * TV Stick, work done here is time before the first frame, and cold start is the metric users
 * actually feel.
 */
class SuStreamApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext = this)
        createNotificationChannels()

        val status = container.config.status()
        AppLog.i(
            TAG,
            "Started. Catalogue=" + (if (status.usingMockCatalogue) "demo" else "TMDB") +
                ", backend=" + (if (status.backendConfigured) status.backendHost else "mock"),
        )
    }

    /**
     * Registers the two notification channels.
     *
     * Both are `IMPORTANCE_LOW`: these are informational — a playlist stopped working, a saved title
     * became available — and neither should interrupt playback with a sound on a television.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId.SERVICE_ALERTS.id,
                getString(R.string.notif_channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_service_description)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId.NEW_CONTENT.id,
                getString(R.string.notif_channel_content_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_content_description)
                setShowBadge(false)
            },
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // A Fire TV Stick reclaims memory aggressively. Dropping the HTTP cache is cheap and
        // recoverable; the player and the database are left alone because losing either mid-session
        // is visible to the user.
        AppLog.w(TAG, "Low memory; clearing the HTTP cache")
        container.httpClientFactory.clearCache()
    }
}
