package com.justprintit.app.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.justprintit.core.layout.ContentBounds
import java.io.Closeable
import java.io.File

/** A printable document: one PDF, or one or more photos (one photo = one page). */
sealed class PrintDocument(val files: List<File>, val name: String) : Closeable {
    abstract val pageCount: Int
    abstract val mime: String
    /** Page size in page units (PDF points or image pixels), after EXIF rotation. */
    abstract fun pageSize(index: Int): Pair<Double, Double>

    /** Draws page [index] into [target], mapping page units through [matrix], limited to [clip]. */
    abstract fun draw(index: Int, target: Bitmap, matrix: Matrix, clip: Rect)

    val isPhotos get() = this is Photos

    private val boundsCache = HashMap<Int, ContentBounds.Fraction?>()

    /** Content area of a page as fractions, for "trim margins". Cached; renders a small copy once. */
    fun contentBounds(index: Int): ContentBounds.Fraction? = synchronized(boundsCache) {
        boundsCache.getOrPut(index) {
            val (w, h) = pageSize(index)
            val scale = 500.0 / maxOf(w, h)
            val bw = (w * scale).toInt().coerceAtLeast(1); val bh = (h * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            draw(index, bmp, Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }, Rect(0, 0, bw, bh))
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            bmp.recycle()
            ContentBounds.find(px, bw, bh)
        }
    }

    class Pdf(file: File, name: String) : PrintDocument(listOf(file), name) {
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try { PdfRenderer(fd) } catch (e: Exception) {
            fd.close()
            throw IllegalArgumentException("This PDF can't be opened (it may be password-protected or damaged).", e)
        }
        private val sizes = HashMap<Int, Pair<Double, Double>>()
        override val pageCount get() = renderer.pageCount
        override val mime = "application/pdf"

        override fun pageSize(index: Int): Pair<Double, Double> = synchronized(renderer) {
            sizes.getOrPut(index) { renderer.openPage(index).use { it.width.toDouble() to it.height.toDouble() } }
        }

        override fun draw(index: Int, target: Bitmap, matrix: Matrix, clip: Rect) = synchronized(renderer) {
            renderer.openPage(index).use { page ->
                page.render(target, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            }
        }

        override fun close() = synchronized(renderer) { renderer.close(); fd.close() }
    }

    class Photos(files: List<File>, name: String) : PrintDocument(files, name) {
        private data class Meta(val rawW: Int, val rawH: Int, val orientation: Int) {
            val swapped get() = orientation in 5..8
            val w get() = if (swapped) rawH else rawW
            val h get() = if (swapped) rawW else rawH
        }

        private val metas = files.map { f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            require(o.outWidth > 0) { "${f.name} is not a picture this phone can read" }
            val orientation = runCatching {
                ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            Meta(o.outWidth, o.outHeight, orientation)
        }
        override val pageCount get() = files.size
        override val mime = "image/*"
        override fun pageSize(index: Int) = metas[index].let { it.w.toDouble() to it.h.toDouble() }

        override fun draw(index: Int, target: Bitmap, matrix: Matrix, clip: Rect) {
            val m = metas[index]
            // Decode only as many pixels as the target area needs (keeps big camera photos in memory budget).
            val scaleToTarget = matrix.mapRadius(1f).toDouble()
            val neededW = (m.rawW * scaleToTarget).coerceAtLeast(1.0)
            var sample = 1
            while (m.rawW / (sample * 2) >= neededW) sample *= 2
            val bmp = BitmapFactory.decodeFile(files[index].absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("Could not read ${files[index].name}")
            val full = Matrix().apply { setScale(m.rawW.toFloat() / bmp.width, m.rawH.toFloat() / bmp.height) }
            full.postConcat(orientationMatrix(m.orientation, m.rawW.toFloat(), m.rawH.toFloat()))
            full.postConcat(matrix)
            val canvas = Canvas(target)
            canvas.save()
            canvas.clipRect(clip)
            canvas.drawBitmap(bmp, full, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
            bmp.recycle()
        }

        override fun close() = Unit

        private fun orientationMatrix(o: Int, w: Float, h: Float) = Matrix().apply {
            when (o) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { postScale(-1f, 1f); postTranslate(w, 0f) }
                ExifInterface.ORIENTATION_ROTATE_180 -> { postRotate(180f); postTranslate(w, h) }
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { postScale(1f, -1f); postTranslate(0f, h) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postTranslate(h, 0f); postScale(-1f, 1f); postTranslate(h, 0f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> { postRotate(90f); postTranslate(h, 0f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postTranslate(0f, w); postScale(-1f, 1f); postTranslate(h, 0f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> { postRotate(270f); postTranslate(0f, w) }
            }
        }
    }

    companion object {
        /** Copies picked/shared files into private storage so the job can be retried later. */
        fun import(context: Context, uris: List<Uri>): PrintDocument {
            require(uris.isNotEmpty())
            val dir = File(context.filesDir, "jobs").apply { mkdirs() }
            val resolver = context.contentResolver
            val stamp = System.currentTimeMillis()
            val copied = uris.mapIndexed { i, uri ->
                val display = displayName(context, uri) ?: "document"
                val mime = resolver.getType(uri) ?: if (display.endsWith(".pdf", true)) "application/pdf" else "image/jpeg"
                val ext = if (mime == "application/pdf") "pdf" else "img"
                val file = File(dir, "${stamp}_${i}.$ext")
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open $display" }
                    file.outputStream().use { input.copyTo(it) }
                }
                Triple(file, display, mime)
            }
            val pdfs = copied.filter { it.third == "application/pdf" }
            return when {
                pdfs.isNotEmpty() -> {
                    copied.filter { it !in pdfs.take(1) }.forEach { it.first.delete() }
                    Pdf(pdfs[0].first, pdfs[0].second)
                }
                else -> Photos(copied.map { it.first }, if (copied.size == 1) copied[0].second else "${copied.size} photos")
            }
        }

        fun open(files: List<File>, mime: String, name: String): PrintDocument =
            if (mime == "application/pdf") Pdf(files.single(), name) else Photos(files, name)

        private fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
