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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
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
    private lateinit var mediaSession: MediaSessionCompat

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
        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !::mediaSession.isInitialized) {
            return START_NOT_STICKY
        }

        when (intent.action) {
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
            ACTION_TOGGLE_PLAYBACK -> togglePlayPause()
            ACTION_SKIP -> skipCurrent()
            ACTION_STOP -> stopPlaybackAndService()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlaybackAndService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
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
            pausePlayback()
        } else {
            resumePlayback()
        }
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

        updateMediaSession(song, PlaybackStateCompat.STATE_PLAYING)
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
        if (currentIndex < previousIndex) {
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

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        resumePlayback()
                    }

                    override fun onPause() {
                        pausePlayback()
                    }

                    override fun onSkipToNext() {
                        skipCurrent()
                    }

                    override fun onStop() {
                        stopPlaybackAndService()
                    }
                }
            )
            isActive = true
        }
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    private fun updateNotification(song: Song) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipIntent = PendingIntent.getService(
            this,
            REQUEST_SKIP,
            Intent(this, PlayerService::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, PlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playing = isPlaying
        mediaSession.setSessionActivity(contentIntent)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist ?: getString(R.string.unknown_artist))
            .setContentIntent(contentIntent)
            .setSubText(
                if (playing) getString(R.string.playback_state_playing)
                else getString(R.string.playback_state_paused)
            )
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) getString(R.string.pause) else getString(R.string.play),
                toggleIntent
            )
            .addAction(android.R.drawable.ic_media_next, getString(R.string.skip), skipIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun pausePlayback() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return
        player.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        onPlayStateChanged?.invoke(false)
        currentSong?.let { updateNotification(it) }
    }

    private fun resumePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) return
        player.start()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        onPlayStateChanged?.invoke(true)
        currentSong?.let { updateNotification(it) }
    }

    private fun updateMediaSession(song: Song, state: Int) {
        val duration = if (song.duration > 0L) song.duration else mediaPlayer?.duration?.toLong() ?: 0L
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    song.artist ?: getString(R.string.unknown_artist)
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
        )
        updatePlaybackState(state)
    }

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, speed, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun stopPlaybackAndService() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: IllegalStateException) {
                // Ignore and continue shutdown.
            }
            player.release()
        }
        mediaPlayer = null
        mediaSession.setMetadata(null)
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        onPlayStateChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val MEDIA_SESSION_TAG = "RulePlaySession"
        private const val CHANNEL_ID = "ruleplay_playback"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_SKIP = 2
        private const val REQUEST_STOP = 3
        private const val ACTION_TOGGLE_PLAYBACK = "com.zackaryw.ruleplay.action.TOGGLE_PLAYBACK"
        private const val ACTION_SKIP = "com.zackaryw.ruleplay.action.SKIP"
        private const val ACTION_STOP = "com.zackaryw.ruleplay.action.STOP"
    }
}
