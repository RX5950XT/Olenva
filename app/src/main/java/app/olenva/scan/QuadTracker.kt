package app.olenva.scan

import kotlin.math.abs

/**
 * Steadies the live outline: frame-to-frame jitter is averaged out, a detection far from the current
 * outline is ignored until it repeats (one-off misreads), and a brief miss keeps the last outline.
 */
class QuadTracker(private val holdMs: Long = 400) {
    private var quad: Quad? = null
    private var seenAt = 0L
    private var candidate: Quad? = null
    private var hits = 0

    fun reset() {
        quad = null
        candidate = null
        hits = 0
    }

    fun update(raw: Quad?, now: Long): Quad? {
        val cur = quad
        if (raw != null) {
            if (cur != null && diff(cur, raw) < JUMP) {
                quad = FloatArray(8) { cur[it] + (raw[it] - cur[it]) * SMOOTH }
                seenAt = now
                candidate = null
                hits = 0
            } else {
                hits = if (candidate?.let { diff(it, raw) < JUMP } == true) hits + 1 else 1
                candidate = raw
                // New page: two agreeing frames. Jump away from a tracked page: three.
                if (hits >= if (cur == null) 2 else 3) {
                    quad = raw.copyOf()
                    seenAt = now
                    candidate = null
                    hits = 0
                }
            }
        }
        if (quad != null && now - seenAt > holdMs) quad = null
        return quad?.copyOf()
    }

    companion object {
        private const val JUMP = 0.08f
        private const val SMOOTH = 0.5f

        fun diff(a: Quad, b: Quad) = (0 until 8).maxOf { abs(a[it] - b[it]) }
    }
}
