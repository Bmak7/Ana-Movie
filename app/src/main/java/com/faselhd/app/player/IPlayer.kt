package com.faselhd.app.player

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.faselhd.app.models.Video

// A simple event system to communicate from the player back to the UI
sealed class PlayerEvent {
    data class OnPlayerError(val error: Exception) : PlayerEvent()
    data class OnPlaybackStateChanged(val state: Int) : PlayerEvent()
    data class OnIsPlayingChanged(val isPlaying: Boolean) : PlayerEvent()
    object OnTracksChanged : PlayerEvent()
    object OnVideoSizeChanged : PlayerEvent()
}

// The main player interface
interface IPlayer {
    fun initialize(
        context: Context,
        playerView: PlayerView,
        listener: (PlayerEvent) -> Unit
    )

    fun loadMedia(video: Video, startPosition: Long)
    fun play()
    fun pause()
    fun release()
    fun seekTo(positionMs: Long)
    fun seekForward()
    fun seekRewind()

    fun getPlayer(): androidx.media3.common.Player?
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun isPlaying(): Boolean

    // New methods for track selection
    fun setPlaybackSpeed(speed: Float)

    fun getTrackSelector(): androidx.media3.exoplayer.trackselection.DefaultTrackSelector?
    fun showSubtitleSelectionDialog(activity: AppCompatActivity)
    fun showAudioTrackSelectionDialog(activity: AppCompatActivity)
}