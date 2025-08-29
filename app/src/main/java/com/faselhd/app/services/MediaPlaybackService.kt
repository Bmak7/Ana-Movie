// First, create a MediaPlaybackService.kt
package com.faselhd.app.service

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.faselhd.app.VideoPlayerActivity
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.Video
import com.example.myapplication.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URL

class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "action_play_pause"
        const val ACTION_NEXT = "action_next"
        const val ACTION_PREVIOUS = "action_previous"
        const val ACTION_STOP = "action_stop"
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentAnime: SAnime? = null
    private var currentEpisode: SEpisode? = null
    private var currentVideo: Video? = null

    private val binder = MediaPlaybackBinder()

    inner class MediaPlaybackBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeMediaSession()
        initializePlayer()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState(isPlaying)
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackState(exoPlayer?.isPlaying ?: false)
                updateNotification()
            }
        })
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MediaPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    exoPlayer?.play()
                }

                override fun onPause() {
                    exoPlayer?.pause()
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    exoPlayer?.seekTo(pos)
                }
            })
            isActive = true
        }
    }

    fun playVideo(video: Video, anime: SAnime, episode: SEpisode) {
        currentVideo = video
        currentAnime = anime
        currentEpisode = episode

        val mediaItem = MediaItem.fromUri(video.url)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.play()

        updateMediaSessionMetadata()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun updateMediaSessionMetadata() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, episode.name ?: "Unknown Episode")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, anime.title ?: "Unknown Anime")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, anime.title ?: "Unknown Anime")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exoPlayer?.duration ?: 0)

        // Load artwork asynchronously
        anime.thumbnail_url?.let { thumbnailUrl ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = BitmapFactory.decodeStream(URL(thumbnailUrl).openConnection().getInputStream())
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                    mediaSession?.setMetadata(metadataBuilder.build())
                } catch (e: IOException) {
                    // Use default artwork or continue without it
                    mediaSession?.setMetadata(metadataBuilder.build())
                }
            }
        } ?: run {
            mediaSession?.setMetadata(metadataBuilder.build())
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = exoPlayer?.currentPosition ?: 0

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, 1.0f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun createNotification(): Notification {
        val anime = currentAnime
        val episode = currentEpisode
        val isPlaying = exoPlayer?.isPlaying ?: false

        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large,
            if (isPlaying) "Pause" else "Play",
            PendingIntent.getService(
                this, 0,
                Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            "Next",
            PendingIntent.getService(
                this, 0,
                Intent(this, MediaPlaybackService::class.java).setAction(ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val stopAction = NotificationCompat.Action(
            R.drawable.ic_close,
            "Stop",
            PendingIntent.getService(
                this, 0,
                Intent(this, MediaPlaybackService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(episode?.name ?: "Unknown Episode")
            .setContentText(anime?.title ?: "Unknown Anime")
            .setSmallIcon(R.drawable.ic_play_arrow_large)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(stopAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = createNotification()
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    private fun playNext() {
        // Implement next episode logic here
        // You'll need to pass episode list to the service
    }

    private fun playPrevious() {
        // Implement previous episode logic here
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0

    fun getDuration(): Long = exoPlayer?.duration ?: 0

    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        mediaSession?.release()
    }
}
