package com.zackaryw.ruleplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zackaryw.ruleplay.model.Song

/**
 * Foreground service that owns the [MediaPlayer] and delegates all skip/completion events
 * to [RuleEngine].
 *
 * Clients bind to this service via [LocalBinder] and register callbacks to react to state
 * changes without polling.
 */
class PlayerService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()

    // ── Playback state ────────────────────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null

    /** The current shuffled playlist. */
    var playlist: List<Song> = emptyList()
        private set

    private var currentIndex: Int = 0

    /** Rule engine instance shared with the bound activity. */
    val ruleEngine = RuleEngine()

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying ?: false
    val currentSong: Song? get() = playlist.getOrNull(currentIndex)

    // ── Callbacks (set by the bound Activity) ─────────────────────────────────

    /** Invoked on the service thread when the current song changes. */
    var onSongChanged: ((Song) -> Unit)? = null

    /** Invoked when playback starts (`true`) or pauses (`false`). */
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    /** Invoked when the user attempts to skip but Rule 1 has locked skipping. */
    var onSkipBlocked: (() -> Unit)? = null

    /**
     * Invoked when Rule 2 forces a song to play.  The callback receives the song that is being
     * force-played so the UI can inform the user.
     */
    var onForcePlay: ((Song) -> Unit)? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Replaces the current playlist with [songs] (shuffled), resets rule state, and starts
     * playback from the first track.
     */
    fun loadPlaylist(songs: List<Song>) {
        ruleEngine.reset()
        playlist = songs.shuffled()
        currentIndex = 0
        if (playlist.isNotEmpty()) playCurrent()
    }

    /**
     * Attempts to skip the current song.  The [RuleEngine] decides whether the skip is allowed;
     * if not, [onSkipBlocked] is fired and playback continues unchanged.
     */
    fun skipCurrent() {
        val song = currentSong ?: return
        if (!ruleEngine.onSkipAttempt(song.id)) {
            onSkipBlocked?.invoke()
            return
        }
        advanceToNext()
    }

    /** Toggles between play and pause. */
    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            onPlayStateChanged?.invoke(false)
        } else {
            mp.start()
            onPlayStateChanged?.invoke(true)
        }
        currentSong?.let { updateNotification(it) }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Starts playing [playlist][currentIndex].
     *
     * If Rule 2 says this song should be force-played, [onForcePlay] is fired **before** playback
     * starts so the UI can display a notice; the force-play activation is recorded in the engine.
     */
    private fun playCurrent() {
        val song = playlist.getOrNull(currentIndex) ?: return

        // Rule 2 check: force-play if the song has been skipped too many times.
        if (ruleEngine.shouldForcePlay(song.id)) {
            ruleEngine.onForcePlayActivated(song.id)
            onForcePlay?.invoke(song)
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@PlayerService, song.uri)
            prepare()
            start()
            setOnCompletionListener { onCurrentSongCompleted() }
        }

        onSongChanged?.invoke(song)
        onPlayStateChanged?.invoke(true)
        updateNotification(song)
    }

    /** Called by [MediaPlayer.OnCompletionListener] when a track finishes naturally. */
    private fun onCurrentSongCompleted() {
        val song = currentSong ?: return
        ruleEngine.onSongCompleted(song.id)
        advanceToNext()
    }

    /** Moves to the next song, wrapping around and notifying the engine of loop boundaries. */
    private fun advanceToNext() {
        val previousIndex = currentIndex
        currentIndex = (currentIndex + 1) % playlist.size
        if (currentIndex <= previousIndex) {
            // Wrapped around – a full loop has completed.
            ruleEngine.onLoopCompleted()
        }
        playCurrent()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun updateNotification(song: Song) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist ?: getString(R.string.unknown_artist))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "ruleplay_playback"
        private const val NOTIFICATION_ID = 1
    }
}
