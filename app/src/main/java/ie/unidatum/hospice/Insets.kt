package ie.unidatum.hospice

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Every screen in this app draws edge to edge, which Android 15 makes the only
 * option anyway. Left alone, that puts the first line of a screen behind the
 * status bar and the last button behind the navigation bar, and it leaves the
 * system's own clock and icons drawn light over this app's light background.
 *
 * [fitSystemBars] settles both. It keeps whatever padding the layout already
 * asked for and adds the system bars on top, so a screen starts below the clock
 * and ends above the navigation bar on any handset, whatever the shape of its
 * cutout or gesture area. It also tells the system to draw its bars' icons dark,
 * which is what this app's background wants.
 *
 * Pass keyboard = true on a screen with text fields, and the foot of the screen
 * rises with the keyboard instead of hiding behind it.
 */
fun Activity.fitSystemBars(root: View, keyboard: Boolean = false) {
    // One code path on every version: take the bars off the decor view's hands
    // and inset the content here, rather than have the system do it below 15
    // and this code do it above.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, root).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }

    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    val types = if (keyboard) WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                else WindowInsetsCompat.Type.systemBars()

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(types)
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
