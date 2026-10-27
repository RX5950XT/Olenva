package app.olenva.scan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Finds which way a page's text faces with on-device OCR (Chinese model, which also reads Latin).
 * ML Kit reads rotated text as-is and reports each line's angle, so one pass is enough: every line votes
 * for the quarter-turn that would make it level, weighted by how much confident text it holds.
 * Returns the clockwise rotation that makes the page upright (0 when unsure or textless),
 * or null when OCR could not run (model still downloading, no Play services) so the caller can retry later.
 */
object Orientation {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** Asks Play services to fetch the OCR model now, so the first scan doesn't miss out. */
    fun prewarm(context: Context) {
        ModuleInstall.getClient(context)
            .installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build())
            .addOnFailureListener { Log.w("Orientation", "OCR model install request failed: ${it.javaClass.simpleName}") }
    }

    suspend fun detect(page: Bitmap): Int? = try {
        val text = recognizer.process(InputImage.fromBitmap(page.scaledTo(1024), 0)).await()
        val votes = mutableMapOf<Int, Double>()
        text.textBlocks.forEach { b ->
            b.lines.forEach { l -> votes.merge(correctionFor(l.angle), weight(l), Double::plus) }
        }
        Log.d("Orientation", "votes=$votes")
        pick(votes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // OCR model not downloaded yet / Play services unavailable: leave the page as shot.
        Log.w("Orientation", "detection skipped: ${e.javaClass.simpleName}")
        null
    }

    /** Clockwise quarter-turn that levels a line drawn at [angle] degrees (clockwise positive). */
    fun correctionFor(angle: Float): Int = Math.floorMod(-Math.round(angle / 90f) * 90, 360)

    /** Pure decision rule, kept separate so it can be unit-tested. */
    fun pick(votes: Map<Int, Double>): Int {
        val total = votes.values.sum()
        if (total < MIN_TEXT) return 0                       // a photo or a nearly blank page
        val (best, w) = votes.maxByOrNull { it.value } ?: return 0
        return if (w / total >= DOMINANCE) best else 0       // mixed directions: leave it alone
    }

    private fun weight(l: Text.Line): Double = l.elements.sumOf { e ->
        val chars = e.text.count { !it.isWhitespace() }
        // Confidence is 0 on some backends; then count characters alone.
        chars * (if (e.confidence > 0f) e.confidence.toDouble() else 0.5)
    }

    private fun Bitmap.scaledTo(maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(width, height)
        return if (s >= 1f) this else Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
    }

    private const val MIN_TEXT = 12.0
    private const val DOMINANCE = 0.6
}
