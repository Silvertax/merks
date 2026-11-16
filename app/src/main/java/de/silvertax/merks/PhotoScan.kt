package de.silvertax.merks

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.googlecode.tesseract.android.TessBaseAPI
import de.silvertax.merks.parse.AiParser
import de.silvertax.merks.parse.AiPhotoResult
import de.silvertax.merks.parse.IcsReader
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.OcrWord
import de.silvertax.merks.parse.PhotoDate
import de.silvertax.merks.parse.PhotoFind
import de.silvertax.merks.parse.PhotoReader
import de.silvertax.merks.parse.Pixels
import de.silvertax.merks.parse.PlanReader
import de.silvertax.merks.parse.RuleParser
import de.silvertax.merks.parse.RuleParserEn
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * From a photo, a picture, a PDF, a calendar file or a text to the appointments in it. With an AI
 * key, the AI reads pictures and texts, which also works for handwriting. Without one, or when the
 * AI cannot be reached, the phone reads the text itself and fixed rules find the dates. A calendar
 * file says exactly what it holds and is just read. Whoever reads it, nothing is kept.
 */
object PhotoScan {
    /** Small print in a letter still has enough pixels at this size, and reading stays quick. */
    private const val READ_SIDE = 2400
    private const val READ_SIDE_MIN = 1800

    /** The longest side the AI reads at; it would shrink a larger photo anyway. */
    private const val AI_SIDE = 1568

    /** A letter's appointment is on its first pages; more would only take long. */
    private const val MAX_PAGES = 3
    private const val MAX_PICTURES = 4

    /** So many appointments with titles of their own are a plan. */
    private const val PLAN_SIZE = 6

    /** A message or a text file; longer is no message about an appointment. */
    const val MAX_TEXT = 20_000

    /** The parts that need a camera picture, native code or the network. Tests put stand-ins here. */
    interface Parts {
        /** The photo upright and at most [maxSide] pixels long, or null when it cannot be opened. */
        fun load(file: File, maxSide: Int, minSide: Int): Bitmap?

        /** The text on [picture]. Pass 1 reads it as one block, pass 2 looks for columns. */
        fun text(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): String

        /**
         * The words on [picture] one by one, with their places: for a table, whose columns a text mixes.
         * Pass 1 and pass 2 tell ink from paper in two ways; each reads some words the other misses.
         */
        fun words(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): List<OcrWord>

        fun ai(key: String, model: String, jpegs: List<ByteArray>, now: LocalDateTime, zone: ZoneId): AiPhotoResult

        fun aiText(key: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiPhotoResult

        /** The first [max] pages of the PDF in [file] as pictures in [dir]; empty when it cannot be opened. */
        fun pages(file: File, dir: File, maxSide: Int, max: Int): List<File>
    }

    @VisibleForTesting
    var parts: Parts = Real

    /** Why nothing could be read at all. */
    class Unreadable(val why: Int) : Exception()

    /** What a file is, by its first bytes: names and types given by other apps are often wrong. */
    enum class Content { PICTURE, PDF, CALENDAR, TEXT }

    fun contentOf(file: File): Content {
        val head = file.inputStream().use { input -> ByteArray(512).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val text = String(head, Charsets.UTF_8)
        return when {
            text.startsWith("%PDF-") -> Content.PDF
            IcsReader.isCalendar(text) -> Content.CALENDAR
            // Pictures start with bytes no text has: JPEG, PNG, GIF, WebP, HEIC.
            head.isNotEmpty() && head.take(512).none { it == 0.toByte() } && looksLikeText(head) -> Content.TEXT
            else -> Content.PICTURE
        }
    }

    private fun looksLikeText(head: ByteArray): Boolean {
        val s = String(head, Charsets.UTF_8)
        return s.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') } <= s.length / 50
    }

    /**
     * Reads what came in: files of any kind (pictures, PDFs, calendar files, texts) and a [text]
     * shared on its own. A calendar file says exactly what it holds and goes first; then pictures
     * and pages; then texts. Throws [Unreadable] when nothing could be opened at all.
     */
    fun readAll(ctx: Context, files: List<File>, text: String?): PhotoFind {
        val contents = files.associateWith { runCatching { contentOf(it) }.getOrNull() }
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val calendars = contents.filterValues { it == Content.CALENDAR }.keys.map { IcsReader.read(it.readText().take(MAX_TEXT * 10), now, zone) }
        if (calendars.any { it.dates.isNotEmpty() }) return merged(calendars)
        val texts = (listOfNotNull(text) + contents.filterValues { it == Content.TEXT }.keys.map { it.readText().take(MAX_TEXT) })
            .map { it.trim() }.filter { it.isNotEmpty() }
        val pictures = contents.filterValues { it == Content.PICTURE || it == Content.PDF }.keys.toList()
        if (pictures.isNotEmpty()) {
            val read = try {
                read(ctx, pictures)
            } catch (e: Unreadable) {
                if (texts.isEmpty()) throw e
                null
            }
            if (read != null && (read.dates.isNotEmpty() || texts.isEmpty())) return read
        }
        if (texts.isNotEmpty()) return readText(ctx, texts.joinToString("\n\n"))
        if (calendars.isNotEmpty()) return merged(calendars)
        throw Unreadable(R.string.photo_file_unreadable)
    }

    private fun merged(finds: List<PhotoFind>): PhotoFind {
        if (finds.size == 1) return finds[0]
        val first = finds.firstOrNull { it.dates.isNotEmpty() } ?: finds.first()
        return first.copy(dates = finds.flatMap { it.dates }.sortedWith(compareBy({ it.day }, { it.time })).take(PhotoReader.MAX_LISTED), text = finds.joinToString("\n\n") { it.text })
    }

    /** Reads the photo in [file]. */
    fun read(ctx: Context, file: File): PhotoFind = read(ctx, listOf(file))

    /**
     * Reads the pictures in [files]: photos and the pages of PDFs. Throws [Unreadable] when nothing
     * can be opened or the phone cannot read text; otherwise returns what was found, possibly
     * without any date.
     */
    fun read(ctx: Context, files: List<File>): PhotoFind {
        val pictures = files.flatMap { f ->
            if (runCatching { contentOf(f) }.getOrNull() == Content.PDF) {
                parts.pages(f, File(f.parentFile, f.name + "-pages").apply { mkdirs() }, READ_SIDE, MAX_PAGES)
            } else {
                listOf(f)
            }
        }.take(MAX_PICTURES)
        if (pictures.isEmpty()) throw Unreadable(R.string.photo_file_unreadable)
        // One picture is opened once for both readings; several one after the other, to spare memory.
        val single = if (pictures.size == 1) parts.load(pictures[0], READ_SIDE, READ_SIDE_MIN) ?: throw Unreadable(R.string.photo_unreadable) else null
        fun open(f: File): Bitmap? = single ?: parts.load(f, READ_SIDE, READ_SIDE_MIN)
        fun done(b: Bitmap) {
            if (b !== single) b.recycle()
        }
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val small = lazy { pictures.mapNotNull { f -> open(f)?.let { b -> jpeg(b, AI_SIDE).also { done(b) } } } }
        val ai = Store.askAi(ctx) { key, model -> parts.ai(key, model, small.value, now, zone) }?.find
        if (ai != null && ai.dates.isNotEmpty()) return marked(ctx, ai, pictures, ::open, ::done, now)
        // The AI saw no date (or could not be asked): the phone has a look of its own.
        val offline = try {
            offline(ctx, pictures, ::open, ::done, now)
        } catch (e: Throwable) {
            // Native code that does not load on this phone, or recognition data that cannot be unpacked.
            if (ai != null) return ai
            throw Unreadable(R.string.photo_ocr_failed)
        }
        return if (offline.dates.isEmpty() && ai != null) ai else offline
    }

    private fun offline(ctx: Context, pictures: List<File>, open: (File) -> Bitmap?, done: (Bitmap) -> Unit, now: LocalDateTime): PhotoFind {
        val german = Lang.isGerman(ctx)
        fun read(f: File, pass: Int) = open(f)?.let { b -> parts.text(ctx, b, german, pass).also { done(b) } }
        fun readAll(pass: Int) = pictures.mapNotNull { read(it, pass) }.joinToString("\n\n")
        val texts = pictures.map { read(it, 1) }
        val text = texts.filterNotNull().joinToString("\n\n")
        // A weekly plan is a table: read as one text, its columns get mixed. So it is read once more,
        // word by word with their places, and put back together column by column.
        val plans = pictures.zip(texts).mapNotNull { (f, t) -> if (t != null && PlanReader.looksLikePlan(t)) plan(ctx, f, t, open, done, german, now) else null }
        if (plans.isNotEmpty()) return merged(plans).copy(text = text)
        val first = PhotoReader.read(text, now)
        if (first.dates.isNotEmpty()) return first
        // Read once more the other way: columns kept apart, another threshold for the light.
        val second = PhotoReader.read(readAll(2), now)
        return when {
            second.dates.isNotEmpty() -> second
            second.text.length > first.text.length -> second
            else -> first
        }
    }

    /**
     * A plan the AI read with everything ticked: the AI often does not see what is marked with a
     * highlighter. The phone tells it by the colour, so it reads the plan itself and sets the ticks.
     */
    private fun marked(ctx: Context, ai: PhotoFind, pictures: List<File>, open: (File) -> Bitmap?, done: (Bitmap) -> Unit, now: LocalDateTime): PhotoFind {
        if (ai.dates.size < PLAN_SIZE || ai.dates.any { !it.chosen } || ai.dates.count { it.title != null } < ai.dates.size - 1) return ai
        return try {
            val german = Lang.isGerman(ctx)
            // From the start of the week, so that what is over today is matched as well.
            val monday = now.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay()
            val own = pictures.mapNotNull { plan(ctx, it, "", open, done, german, monday) }.flatMap { it.dates }
            ai.copy(dates = PlanReader.withMarks(ai.dates, own))
        } catch (e: Throwable) {
            // Text recognition that does not run on this phone: the AI's list stays as it is.
            ai
        }
    }

    /**
     * The appointments in the weekly plan on the picture in [file], whose text read as a page is [seen];
     * null when it is no plan after all.
     */
    private fun plan(ctx: Context, file: File, seen: String, open: (File) -> Bitmap?, done: (Bitmap) -> Unit, german: Boolean, now: LocalDateTime): PhotoFind? {
        val picture = open(file) ?: return null
        return try {
            val words = parts.words(ctx, picture, german, 1)
            // Read twice: the second reading finds headings and times the first one lost.
            val more = runCatching { parts.words(ctx, picture, german, 2) }.getOrDefault(emptyList())
            PlanReader.read(words, now, pixels(picture), more, seen)
        } catch (e: Exception) {
            // The plain reading follows.
            null
        } finally {
            done(picture)
        }
    }

    /** The picture a third of its size, as pixels: enough to see the colour of a highlighter. */
    private fun pixels(picture: Bitmap): Pixels? = try {
        val small = Bitmap.createScaledBitmap(picture, (picture.width / 3).coerceAtLeast(1), (picture.height / 3).coerceAtLeast(1), true)
        val argb = IntArray(small.width * small.height)
        small.getPixels(argb, 0, small.width, 0, 0, small.width, small.height)
        Pixels(small.width, small.height, argb, picture.width.toFloat() / small.width).also { if (small !== picture) small.recycle() }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Finds the appointments in a text: a message passed on from another app, or a text file. A
     * letter's dates are found as on a photo; a short message as one says it ("tomorrow at 10
     * dentist") by the rules for speaking.
     */
    fun readText(ctx: Context, text: String): PhotoFind {
        val clean = text.trim().take(MAX_TEXT)
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        if (IcsReader.isCalendar(clean)) return IcsReader.read(clean, now, zone)
        val ai = Store.askAi(ctx) { key, model -> parts.aiText(key, model, clean, now, zone) }?.find
        if (ai != null && ai.dates.isNotEmpty()) return ai.copy(text = clean)
        val found = PhotoReader.read(clean, now)
        if (found.dates.isNotEmpty()) return found.copy(text = clean)
        val spoken = if (Lang.isGerman(ctx)) RuleParser.parse(clean, now) else RuleParserEn.parse(clean, now)
        val start = spoken.start
        if (spoken.kind != Kind.NOTE && start != null && start.isAfter(now)) {
            val date = PhotoDate(
                start.toLocalDate(),
                start.toLocalTime().takeUnless { spoken.allDay },
                spoken.end?.takeIf { !spoken.allDay && it.toLocalDate() == start.toLocalDate() }?.toLocalTime(),
                spoken.end?.toLocalDate()?.takeIf { spoken.allDay && it.isAfter(start.toLocalDate()) },
                source = clean.take(100),
                repeat = spoken.repeat,
            )
            return PhotoFind(spoken.title, null, listOf(date), clean)
        }
        return (ai ?: found).copy(text = clean)
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

        override fun words(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): List<OcrWord> {
            val lang = if (german) "deu" else "eng"
            val dir = Ocr.data(ctx, lang)
            val api = TessBaseAPI()
            try {
                if (!api.init(dir.path, lang, TessBaseAPI.OEM_LSTM_ONLY)) return emptyList()
                // Words wherever they stand, not the lines of a page. First with the threshold that copes
                // with uneven light (Sauvola), then with the plain one (Otsu).
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                api.setVariable("thresholding_method", if (pass == 1) "2" else "0")
                val pixels = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
                api.setImage(pixels)
                api.getUTF8Text()
                val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val it = api.resultIterator ?: return emptyList()
                val out = mutableListOf<OcrWord>()
                try {
                    it.begin()
                    do {
                        val text = it.getUTF8Text(word) ?: continue
                        val box = it.getBoundingRect(word)
                        out += OcrWord(text, box.left, box.top, box.right, box.bottom, it.confidence(word))
                    } while (it.next(word))
                } finally {
                    it.delete()
                }
                return out
            } finally {
                api.recycle()
            }
        }

        override fun ai(key: String, model: String, jpegs: List<ByteArray>, now: LocalDateTime, zone: ZoneId): AiPhotoResult =
            AiParser.readPhoto(key, model, jpegs, now, zone)

        override fun aiText(key: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiPhotoResult =
            AiParser.readText(key, model, text, now, zone)

        /** Each page drawn on white, its longer side [maxSide] pixels: sharp print, which reads well. */
        override fun pages(file: File, dir: File, maxSide: Int, max: Int): List<File> {
            val out = mutableListOf<File>()
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        for (i in 0 until minOf(pdf.pageCount, max)) {
                            pdf.openPage(i).use { page ->
                                val f = maxSide.toFloat() / maxOf(page.width, page.height)
                                val picture = Bitmap.createBitmap((page.width * f).toInt().coerceAtLeast(1), (page.height * f).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                picture.eraseColor(Color.WHITE)
                                page.render(picture, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val target = File(dir, "page${i + 1}.png")
                                target.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                picture.recycle()
                                out += target
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Locked with a password, or not a PDF after all: what was drawn is used.
            }
            return out
        }
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
