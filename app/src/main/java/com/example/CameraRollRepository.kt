package com.example

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RetroMediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val dateAddedSeconds: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

data class ExifMetadataSummary(
    val make: String,
    val model: String,
    val software: String,
    val resolution: String,
    val dateTaken: String,
    val exposure: String,
    val aperture: String,
)

/**
 * Play-Policy-compliant scoped MediaStore repository for saving, querying,
 * reading EXIF metadata, extracting video thumbnails, and deleting Retro3GS media.
 */
class CameraRollRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    suspend fun saveJpeg2048x1536WithExif(bitmap: Bitmap): RetroMediaItem? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        val displayName = "IMG_3GS_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Retro3GS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null

        try {
            // Write to temporary file first so ExifInterface tags are reliably persisted before MediaStore commit
            val tempFile = File.createTempFile("3gs_photo_", ".jpg", context.cacheDir)
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            }

            val exif = ExifInterface(tempFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Apple")
                setAttribute(ExifInterface.TAG_MODEL, "iPhone 3GS")
                setAttribute(ExifInterface.TAG_SOFTWARE, "3.1.3")
                setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
                setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "385/100")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            }
            exif.saveAttributes()

            resolver.openOutputStream(uri)?.use { dest ->
                FileInputStream(tempFile).use { src ->
                    src.copyTo(dest)
                }
            }
            val fileSize = tempFile.length()
            tempFile.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }

            val id = ContentUris.parseId(uri)
            RetroMediaItem(
                id = id,
                uri = uri,
                isVideo = false,
                displayName = displayName,
                dateAddedSeconds = System.currentTimeMillis() / 1000L,
                durationMs = 0L,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = fileSize,
            )
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    suspend fun saveVideoMp4ToMediaStore(tempMp4File: File, durationMs: Long): RetroMediaItem? =
        withContext(Dispatchers.IO) {
            if (!tempMp4File.exists() || tempMp4File.length() == 0L) return@withContext null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "VID_3GS_$timestamp.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.WIDTH, RetroShaderRenderer.VIDEO_WIDTH)
                put(MediaStore.Video.Media.HEIGHT, RetroShaderRenderer.VIDEO_HEIGHT)
                put(MediaStore.Video.Media.DURATION, durationMs)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Retro3GS")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null

            try {
                resolver.openOutputStream(uri)?.use { dest ->
                    FileInputStream(tempMp4File).use { src ->
                        src.copyTo(dest)
                    }
                }
                val size = tempMp4File.length()
                tempMp4File.delete()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                }

                val id = ContentUris.parseId(uri)
                RetroMediaItem(
                    id = id,
                    uri = uri,
                    isVideo = true,
                    displayName = displayName,
                    dateAddedSeconds = System.currentTimeMillis() / 1000L,
                    durationMs = durationMs,
                    width = RetroShaderRenderer.VIDEO_WIDTH,
                    height = RetroShaderRenderer.VIDEO_HEIGHT,
                    sizeBytes = size,
                )
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }

    suspend fun loadRetroCameraRoll(): List<RetroMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RetroMediaItem>()

        // Query app-created photos in MediaStore (scoped, zero broad storage permission required)
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("IMG_3GS_%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                items.add(
                    RetroMediaItem(
                        id = id,
                        uri = uri,
                        isVideo = false,
                        displayName = cursor.getString(nameCol) ?: "IMG_3GS.jpg",
                        dateAddedSeconds = cursor.getLong(dateCol),
                        durationMs = 0L,
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        sizeBytes = cursor.getLong(sizeCol),
                    ),
                )
            }
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
        )
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("VID_3GS_%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                items.add(
                    RetroMediaItem(
                        id = id,
                        uri = uri,
                        isVideo = true,
                        displayName = cursor.getString(nameCol) ?: "VID_3GS.mp4",
                        dateAddedSeconds = cursor.getLong(dateCol),
                        durationMs = cursor.getLong(durationCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        sizeBytes = cursor.getLong(sizeCol),
                    ),
                )
            }
        }

        items.sortedByDescending { it.dateAddedSeconds }
    }

    suspend fun loadThumbnail(item: RetroMediaItem): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(item.uri, Size(256, 256), null)
            } else if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            } else {
                resolver.openInputStream(item.uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun readExifSummary(item: RetroMediaItem): ExifMetadataSummary = withContext(Dispatchers.IO) {
        if (item.isVideo) {
            val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(item.dateAddedSeconds * 1000L))
            return@withContext ExifMetadataSummary(
                make = "Apple",
                model = "iPhone 3GS",
                software = "3.1.3 (H.264 2.2 Mbps)",
                resolution = "${item.width}x${item.height} @ 30fps",
                dateTaken = formattedDate,
                exposure = "1/30 s",
                aperture = "f/2.8",
            )
        }
        try {
            resolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                ExifMetadataSummary(
                    make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Apple",
                    model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "iPhone 3GS",
                    software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "3.1.3",
                    resolution = "${item.width}x${item.height} (3.1 MP)",
                    dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "2009:06:19 12:00:00",
                    exposure = "${exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "0.008"} s",
                    aperture = "f/${exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "2.8"}",
                )
            } ?: defaultPhotoExif(item)
        } catch (_: Exception) {
            defaultPhotoExif(item)
        }
    }

    suspend fun deleteItem(item: RetroMediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.delete(item.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun defaultPhotoExif(item: RetroMediaItem) = ExifMetadataSummary(
        make = "Apple",
        model = "iPhone 3GS",
        software = "3.1.3",
        resolution = "${item.width}x${item.height}",
        dateTaken = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(item.dateAddedSeconds * 1000L)),
        exposure = "0.008 s",
        aperture = "f/2.8",
    )
}
