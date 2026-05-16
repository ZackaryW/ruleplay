package com.zackaryw.ruleplay

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zackaryw.ruleplay.databinding.ActivityMainBinding
import com.zackaryw.ruleplay.model.Song

/**
 * Single-activity entry point.
 *
 * Responsibilities:
 * - Folder selection via the Storage Access Framework document-tree picker.
 * - Binding to [PlayerService] and wiring up UI callbacks.
 * - Updating now-playing text and the rule-status display.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playerService: PlayerService? = null
    private var serviceBound = false

    // ── Service connection ────────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as PlayerService.LocalBinder
            playerService = localBinder.getService().also { attachCallbacks(it) }
            serviceBound = true
            // Refresh UI in case playback was already running.
            playerService?.currentSong?.let { refreshNowPlaying(it) }
            updateRuleStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    // ── Folder picker launcher ────────────────────────────────────────────────

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { loadSongsFromFolder(it) }
        }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start and bind the player service.
        Intent(this, PlayerService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        binding.btnSelectFolder.setOnClickListener { folderPickerLauncher.launch(null) }
        binding.btnPlayPause.setOnClickListener { playerService?.togglePlayPause() }
        binding.btnSkip.setOnClickListener { playerService?.skipCurrent() }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        if (isFinishing) {
            stopService(Intent(this, PlayerService::class.java))
        }
        super.onDestroy()
    }

    // ── Service callbacks ─────────────────────────────────────────────────────

    private fun attachCallbacks(service: PlayerService) {
        service.onSongChanged = { song ->
            runOnUiThread {
                refreshNowPlaying(song)
                updateRuleStatus()
            }
        }
        service.onPlayStateChanged = { playing ->
            runOnUiThread {
                binding.btnPlayPause.text = if (playing) "⏸" else "▶"
                updateRuleStatus()
            }
        }
        service.onSkipBlocked = {
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.skip_locked_message),
                    Toast.LENGTH_SHORT
                ).show()
                updateRuleStatus()
            }
        }
        service.onForcePlay = { song ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.force_play_message, song.title),
                    Toast.LENGTH_SHORT
                ).show()
                updateRuleStatus()
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshNowPlaying(song: Song) {
        binding.tvSongTitle.text = song.title
        binding.tvArtist.text = song.artist ?: getString(R.string.unknown_artist)
    }

    private fun updateRuleStatus() {
        val service = playerService ?: return
        val state = service.ruleEngine.getState()
        binding.tvRuleStatus.text = buildString {
            append(getString(R.string.rule1_status, state.consecutiveSkips, state.skipThreshold))
            if (state.skipLocked) append(" \uD83D\uDD12 ${getString(R.string.locked)}")
            append("\n")
            append(getString(R.string.rule2_status, state.forcePlayActivationsThisLoop))
            if (state.isRule2Suspended) append(" (${getString(R.string.suspended)})")
        }
    }

    // ── Folder loading ────────────────────────────────────────────────────────

    private fun loadSongsFromFolder(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission already held or not grantable; continue anyway.
        }

        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val songs = mutableListOf<Song>()
        val cursor: Cursor? = contentResolver.query(childrenUri, projection, null, null, null)
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val mime = c.getString(mimeCol)
                if (!mime.startsWith("audio/")) continue
                val childDocId = c.getString(idCol)
                val displayName = c.getString(nameCol)
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                songs.add(
                    Song(
                        id = childDocId,
                        uri = fileUri,
                        title = displayName.substringBeforeLast('.')
                    )
                )
            }
        }

        if (songs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_audio_files), Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(
            this, getString(R.string.songs_loaded, songs.size), Toast.LENGTH_SHORT
        ).show()
        playerService?.loadPlaylist(songs)
        binding.btnSelectFolder.text = getString(R.string.change_folder)
    }
}
