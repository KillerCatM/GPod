package com.gpod.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background playback: notification, lock screen, headset + Bluetooth controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.repeatMode = prefs.repeatMode
        player.shuffleModeEnabled = prefs.shuffle
        player.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) { prefs.repeatMode = repeatMode }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { prefs.shuffle = enabled }
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Items crossing the controller boundary lose their URI; rebuild it.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map {
                        it.buildUpon().setUri(it.requestMetadata.mediaUri ?: Uri.parse(it.mediaId)).build()
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SLEEP) {
            session?.player?.run {
                pause()
                stop()
                clearMediaItems() // removes the media notification
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_SLEEP = "com.gpod.player.SLEEP"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping GPod away in Recents fully stops playback, like Exit. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.run {
            // remember where we were so reopening resumes at this spot
            if (mediaItemCount > 0) {
                Prefs(this@PlaybackService).queueCurrent = currentMediaItem?.mediaId ?: ""
                Prefs(this@PlaybackService).queuePosition = currentPosition
            }
            pause()
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
