package com.zackaryw.ruleplay.model

import android.net.Uri

/**
 * Represents a single audio file in the playlist.
 *
 * @param id      Unique identifier (DocumentsContract document ID or URI string).
 * @param uri     Content URI used by MediaPlayer / MediaDataSource.
 * @param title   Display title (file name with extension stripped).
 * @param artist  Optional artist tag read from media metadata.
 * @param duration Track duration in milliseconds (0 if unknown).
 */
data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val duration: Long = 0L
)
