package com.faselhd.app.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.exoplayer.ExoPlayer
import com.example.myapplication.R
import com.faselhd.app.VideoPlayerActivityEnhanced
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode

class MediaSessionHelper(
    private val context: Context,
    private val player: ExoPlayer,
    private val callbacks: MediaSessionCallbacks
) {

    interface MediaSessionCallbacks {
        fun onPlay()
        fun onPause()
        fun onSeekTo(position: Long)
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onStop()
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var currentAnime: SAnime? = null
    private var currentEpisode: SEpisode? = null

    fun initialize() {
        mediaSession = MediaSessionCompat(context, "VideoPlayerSession")
        
        // Set session activity (what happens when user taps notification)
        val sessionIntent = Intent(context, VideoPlayerActivityEnhanced::class.java)
        val sessionPendingIntent = PendingIntent.getActivity(
            context, 0, sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession.setSessionActivity(sessionPendingIntent)

        // Set callback for media session events
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                callbacks.onPlay()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }

            override fun onPause() {
                callbacks.onPause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }

            override fun onSeekTo(pos: Long) {
                callbacks.onSeekTo(pos)
            }

            override fun onSkipToNext() {
                callbacks.onSkipToNext()
            }

            override fun onSkipToPrevious() {
                callbacks.onSkipToPrevious()
            }

            override fun onStop() {
                callbacks.onStop()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            }
        })

        mediaSession.isActive = true
    }

    fun updateMetadata(anime: SAnime?, episode: SEpisode?) {
        currentAnime = anime
        currentEpisode = episode

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, episode?.name ?: "Unknown Episode")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, anime?.title ?: "Unknown Series")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Episode ${episode?.episode_number ?: ""}")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, getDefaultArtwork())
            .build()

        mediaSession.setMetadata(metadata)
    }

    fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .setActions(actions)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    fun updatePosition() {
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        updatePlaybackState(state)
    }

    private fun getDefaultArtwork(): Bitmap {
        return BitmapFactory.decodeResource(context.resources, R.drawable.default_video_thumbnail)
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
    }

    fun getMediaSession(): MediaSessionCompat = mediaSession
}

