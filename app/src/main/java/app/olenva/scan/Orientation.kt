package app.olenva.scan

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

/**
 * Which way is up on a cropped page. PaddleOCR's document orientation classifier (PP-LCNet_x1_0_doc_ori, Apache-2.0,
 * see third_party/PP-LCNet_doc_ori) looks at the page layout, so it also gets handwriting right, where OCR line angles
 * flipped handwritten math upside down. ~60 ms per page on a Pixel 6a, most of it copying the bitmap.
 */
object Orientation {
    private const val MODEL = "doc_ori_lcnet.onnx"
    private const val IN = 224.0
    // Pages score ≥ 0.89 for their real direction; a desk photo that wasn't cropped still scores up to ~0.78.
    private const val MIN_CONFIDENCE = 0.8f
    private val LABELS = intArrayOf(0, 90, 180, 270)   // clockwise turn the page has in the image
    @Volatile private var net: Net? = null

    fun load(context: Context) {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        net = Dnn.readNetFromONNX(MatOfByte(*bytes))
    }

    /** Clockwise quarter-turn that makes [page] upright, or null while the model loads or when it isn't sure (blank page, photo). */
    @Synchronized fun detect(page: Bitmap): Int? {
        val n = net ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val x = Mat()
        // The model was trained on whole pages squashed to a square, not on a centre crop.
        Imgproc.resize(rgba, x, Size(IN, IN), 0.0, 0.0, Imgproc.INTER_AREA)
        rgba.release()
        Imgproc.cvtColor(x, x, Imgproc.COLOR_RGBA2RGB)
        x.convertTo(x, CvType.CV_32FC3, 1 / 255.0)
        Core.subtract(x, Scalar(0.485, 0.456, 0.406), x)
        Core.divide(x, Scalar(0.229, 0.224, 0.225), x)
        val blob = Dnn.blobFromImage(x)
        x.release()
        n.setInput(blob)
        val out = n.forward()
        blob.release()
        val probs = FloatArray(4)
        out.reshape(1, 1).get(0, 0, probs)
        out.release()
        return pick(probs)
    }

    /** Pure decision rule, kept separate so it can be unit-tested. */
    fun pick(probs: FloatArray): Int? {
        val best = probs.indices.maxBy { probs[it] }
        if (probs[best] < MIN_CONFIDENCE) return null
        return (360 - LABELS[best]) % 360
    }
}
