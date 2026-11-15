package de.silvertax.merks

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.annotation.VisibleForTesting
import com.googlecode.tesseract.android.TessBaseAPI
import de.silvertax.merks.parse.AiParser
import de.silvertax.merks.parse.AiPhotoResult
import de.silvertax.merks.parse.PhotoFind
import de.silvertax.merks.parse.PhotoReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * From the camera's photo to the appointments on it. With an AI key, the AI reads the photo, which
 * also works for handwriting. Without one, or when the AI cannot be reached, the phone reads the text
 * itself and fixed rules find the dates. Whoever reads it, the photo is not kept.
 */
object PhotoScan {
    /** Small print in a letter still has enough pixels at this size, and reading stays quick. */
    private const val READ_SIDE = 2400
    private const val READ_SIDE_MIN = 1800

    /** The longest side the AI reads at; it would shrink a larger photo anyway. */
    private const val AI_SIDE = 1568

    /** The parts that need a camera picture, native code or the network. Tests put stand-ins here. */
    interface Parts {
        /** The photo upright and at most [maxSide] pixels long, or null when it cannot be opened. */
        fun load(file: File, maxSide: Int, minSide: Int): Bitmap?

        /** The text on [picture]. Pass 1 reads it as one block, pass 2 looks for columns. */
        fun text(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): String

        fun ai(key: String, model: String, jpeg: ByteArray, now: LocalDateTime, zone: ZoneId): AiPhotoResult
    }

    @VisibleForTesting
    var parts: Parts = Real

    /** Why nothing could be read at all. */
    class Unreadable(val why: Int) : Exception()

    /**
     * Reads the photo in [file]. Throws [Unreadable] when the photo cannot be opened or the phone
     * cannot read text; otherwise returns what was found, possibly without any date.
     */
    fun read(ctx: Context, file: File): PhotoFind {
        val picture = parts.load(file, READ_SIDE, READ_SIDE_MIN) ?: throw Unreadable(R.string.photo_unreadable)
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val small = lazy { jpeg(picture, AI_SIDE) }
        val ai = Store.askAi(ctx) { key, model -> parts.ai(key, model, small.value, now, zone) }?.find
        if (ai != null && ai.dates.isNotEmpty()) return ai
        // The AI saw no date (or could not be asked): the phone has a look of its own.
        val offline = try {
            offline(ctx, picture, now)
        } catch (e: Throwable) {
            // Native code that does not load on this phone, or recognition data that cannot be unpacked.
            if (ai != null) return ai
            throw Unreadable(R.string.photo_ocr_failed)
        }
        return if (offline.dates.isEmpty() && ai != null) ai else offline
    }

    private fun offline(ctx: Context, picture: Bitmap, now: LocalDateTime): PhotoFind {
        val german = Lang.isGerman(ctx)
        val first = PhotoReader.read(parts.text(ctx, picture, german, 1), now)
        if (first.dates.isNotEmpty()) return first
        // Read once more the other way: columns kept apart, another threshold for the light.
        val second = PhotoReader.read(parts.text(ctx, picture, german, 2), now)
        return when {
            second.dates.isNotEmpty() -> second
            second.text.length > first.text.length -> second
            else -> first
        }
    }

    /** The picture as a JPEG of at most [side] pixels, as the AI gets it. */
    fun jpeg(picture: Bitmap, side: Int): ByteArray {
        val small = scaled(picture, side)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (small !== picture) small.recycle()
        return out.toByteArray()
    }

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= side) return b
        val f = side.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private object Real : Parts {
        // The platform's ExifInterface had its known flaws before Android 7.1; the app needs 8.0 or newer.
        @SuppressLint("ExifInterface")
        override fun load(file: File, maxSide: Int, minSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val long = maxOf(bounds.outWidth, bounds.outHeight)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // Decoded at a fraction straight away: a 12-megapixel photo in full would take 48 MB.
            var sample = 1
            while (long / (sample * 2) >= minSide) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val picture = scaled(decoded, maxSide).also { if (it !== decoded) decoded.recycle() }
            // Many cameras save the photo lying on its side and only note how to turn it.
            val exif = runCatching { android.media.ExifInterface(file.path) }.getOrNull()
            val turn = when (exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (turn == 0f) return picture
            val upright = Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, Matrix().apply { postRotate(turn) }, true)
            if (upright !== picture) picture.recycle()
            return upright
        }

        override fun text(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): String {
            val lang = if (german) "deu" else "eng"
            val dir = Ocr.data(ctx, lang)
            val api = TessBaseAPI()
            try {
                if (!api.init(dir.path, lang, TessBaseAPI.OEM_LSTM_ONLY)) return ""
                if (pass == 1) {
                    // One block of text, with a threshold that copes with uneven light (Sauvola).
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                    api.setVariable("thresholding_method", "2")
                } else {
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                }
                // Tesseract takes only this pixel format.
                val pixels = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
                api.setImage(pixels)
                return api.getUTF8Text().orEmpty()
            } finally {
                api.recycle()
            }
        }

        override fun ai(key: String, model: String, jpeg: ByteArray, now: LocalDateTime, zone: ZoneId): AiPhotoResult =
            AiParser.readPhoto(key, model, jpeg, now, zone)
    }
}

/** The recognition data comes inside the app and is unpacked once for Tesseract, which reads files. */
object Ocr {
    /** The folder that holds "tessdata/[lang].traineddata", unpacked from the app if needed. */
    @Synchronized
    fun data(ctx: Context, lang: String): File {
        val root = File(ctx.noBackupFilesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        val target = File(dir, "$lang.traineddata")
        val stamp = File(dir, "$lang.version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (target.isFile && stamp.isFile && stamp.readText() == version) return root
        val part = File(dir, "$lang.part")
        ctx.assets.open("tessdata/$lang.traineddata").use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(target)) {
            target.delete()
            part.renameTo(target)
        }
        stamp.writeText(version)
        return root
    }
}
