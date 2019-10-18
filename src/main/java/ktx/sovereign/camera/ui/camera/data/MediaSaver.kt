@file:JvmName("MediaSaverUtils")
package ktx.sovereign.camera.ui.camera.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.edit
import ktx.sovereign.database.provider.MediaProvider
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

private const val MEDIA_SAVER_PREFS = "MediaSaver"
private const val YUV_FILE_PREFIX = "Depth_"
private const val YUV_FILE_SUFFIX = ".img"
private const val JPEG_FILE_PREFIX = "img_"
private const val JPEG_FILE_SUFFIX = ".jpg"
fun getNextInt(context: Context, id: String): Int {
    val prefs = context.getSharedPreferences(MEDIA_SAVER_PREFS, Context.MODE_PRIVATE)
    val i: Int = prefs.getInt(id, 1)
    prefs.edit {
        putInt(id, i+1)
    }
    return i
}
fun saveDepth(context: Context, depthCloudData: ByteBuffer): String? {
    val name = StringBuilder()
    try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null // File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OpenCamera")
        if (!dir.exists() && !dir.mkdir()) return null
        val i = getNextInt(context, "depth_counter")
        name.append(YUV_FILE_PREFIX).append(String.format("%05d", i)).append(YUV_FILE_SUFFIX)
        val file = File(dir, name.toString())
        if (!file.createNewFile()) throw IOException("Failed to create file: ${file.name}")
        var byteCount = 0
        file.outputStream().use { stream ->
            stream.channel.use { channel ->
                while (depthCloudData.hasRemaining()) {
                    byteCount = channel.write(depthCloudData)
                    if (byteCount == 0) {
                        throw IOException("Failed to write file: Byte Count: $byteCount")
                    }
                }
            }
            stream.flush()
        }
        insertImage(context.contentResolver, file)
    } catch (ex: IOException) {
        Log.e("MediaSaver", "${ex.message}")
        return null
    }
    return name.toString()
}

fun saveJpeg(context: Context, jpegData: ByteArray): Uri? {
    val name = StringBuilder()
    val file: File
    try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null // File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OpenCamera")
        if (!dir.exists() && !dir.mkdir()) return null
        val i = getNextInt(context, "counter")
        name.append(JPEG_FILE_PREFIX).append(String.format("%05d", i)).append(JPEG_FILE_SUFFIX)
        file = File(dir, name.toString())
        if (!file.createNewFile()) throw IOException("Failed to create file: ${file.name}")
        file.outputStream().use { stream ->
            stream.write(jpegData)
            stream.flush()
        }
        insertImage(context.contentResolver, file)
        Log.v("MediaSaver", "Created file at ${file.absolutePath}")
    } catch (ex: IOException) {
        Log.e("MediaSaver", "${ex.message}")
        return null
    }
    return FileProvider.getUriForFile(context, MediaProvider.Authority, file)
}

fun insertImage(resolver: ContentResolver, file: File) {
    val stamp = System.currentTimeMillis()
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.TITLE, file.name)
        put(MediaStore.Images.Media.DISPLAY_NAME, file.nameWithoutExtension)
        put(MediaStore.Images.Media.DESCRIPTION, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension))
        put(MediaStore.Images.Media.DATE_ADDED, stamp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.DATE_TAKEN, stamp)
        }
    }
    try {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } catch(ex: Exception) {
        Log.w("MediaSaver", "Error while updating Media Store for $file", ex)
    }
}