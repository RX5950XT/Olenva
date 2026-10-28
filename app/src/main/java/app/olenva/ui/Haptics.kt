package app.olenva.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

// Every tap in the app goes through these. They follow the system touch-feedback switch; the camera's own
// haptics toggle only covers shooting.

/** [onClick] with a light click first. */
@Composable
fun haptic(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
}

/** [onChange] (switches, checkboxes) with a light click first. */
@Composable
fun hapticToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onChange(it) }
}

/** One detent of a stepped slider. */
fun View.tick() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
)

/** Something landed where it should (a scanned page in the composer). */
fun View.confirm() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
)
