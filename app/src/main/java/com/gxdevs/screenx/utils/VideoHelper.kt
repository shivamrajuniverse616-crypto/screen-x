package com.gxdevs.screenx.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val resolution: String
)

object VideoHelper {

    @SuppressLint("Range")
    fun fetchVideos(context: Context): List<RecordedVideo> {
        val videos = mutableListOf<RecordedVideo>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        
        // Define columns to retrieve
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RESOLUTION
        )

        // Query files inside Movies/ScreenX or files starting with ScreenX
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%Movies/ScreenX%")

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                    val resolution = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION)) ?: "Unknown"

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    // Verify file still exists on disk
                    if (File(path).exists()) {
                        videos.add(
                            RecordedVideo(
                                id = id,
                                uri = contentUri,
                                name = name,
                                path = path,
                                size = size,
                                duration = duration,
                                dateAdded = dateAdded,
                                resolution = resolution
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return videos
    }

    fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun formatDate(timestampS: Long): String {
        val date = Date(timestampS * 1000)
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(date)
    }

    /**
     * Deletes a video. On Android 10+, this might throw a RecoverableSecurityException
     * which needs to be caught in the activity to prompt the user.
     */
    fun deleteVideo(context: Context, video: RecordedVideo, onRecoverableException: (Intent) -> Unit = {}): Boolean {
        return try {
            val rowsDeleted = context.contentResolver.delete(video.uri, null, null)
            rowsDeleted > 0
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableSecurityException = securityException as? RecoverableSecurityException
                    ?: throw securityException
                val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                // Pass it back to the caller to startIntentSenderForResult
                val intent = Intent().apply {
                    putExtra("intent_sender", intentSender)
                }
                onRecoverableException(intent)
                false
            } else {
                throw securityException
            }
        }
    }

    fun shareVideo(context: Context, video: RecordedVideo) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Screen Recording"))
    }
}
