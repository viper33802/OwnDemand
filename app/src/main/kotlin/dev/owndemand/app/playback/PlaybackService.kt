package dev.owndemand.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.owndemand.app.MainActivity
import dev.owndemand.app.OwnDemandApp

/**
 * Playback outside the app (Chunk 4): the media notification, lock-screen
 * and headset controls, and the foreground service that keeps audio going
 * in the background. The player itself stays in [PlaybackManager]; the
 * session wraps it so previous / next follow the app's own rules.
 *
 * Not exported: only the app binds to it (AppContainer, once a video plays).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as OwnDemandApp).container
        // No server yet (should not happen: the app binds only once a video
        // plays) — run without a session and let the binding be refused.
        if (container.currentServer == null) return
        val manager = container.playbackManager()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SessionPlayer(manager))
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(container.artworkLoader))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away from Recents ends playback, like closing the miniplayer. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as OwnDemandApp).container.stopPlaybackFromSystem()
        stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to PlaybackManager: release the session only.
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * The app's player as the session sees it: previous / next are always
 * offered and go to [PlaybackManager]'s history and Up next (there is no
 * playlist in the player itself).
 */
@OptIn(UnstableApi::class)
private class SessionPlayer(private val manager: PlaybackManager) : ForwardingPlayer(manager.player) {

    private val skipCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*skipCommands).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in skipCommands || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = true

    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = manager.skipToNext()

    override fun seekToNextMediaItem() = manager.skipToNext()

    override fun seekToPrevious() = manager.skipToPrevious()

    override fun seekToPreviousMediaItem() = manager.skipToPrevious()
}
