package com.omkod.easycam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import timber.log.Timber

class ImageSaver constructor(
        private val image: Image,
        private val isFaceCamera: Boolean,
        private val degrees: Int,
        private val photoCallback: ((bitmap: Bitmap) -> Unit)
) : Runnable {

    override fun run() {
        try {
            val buffer = image.planes[0].buffer
            if (buffer.remaining() > 0) {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val m = Matrix()
                if (isFaceCamera) {
                    m.preScale(-1f, 1f)
                }
                m.postRotate(degrees.toFloat())
                photoCallback.invoke(applyMatrix(source, m))
            }
            image.close()
        } catch (throwable: Throwable) {
            Timber.e(throwable)
        }
    }

    private fun applyMatrix(bitmap: Bitmap, matrix: Matrix): Bitmap {
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }
}