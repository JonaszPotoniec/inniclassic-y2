package com.themoon.y1.controllers.settings

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode

/**
 * An iPod-style list screen in place of an Android dialog: the title in the status bar, grey info rows (the wheel
 * skips them) and one row for each action. The centre button does the action of the selected row; Back (Menu)
 * closes the screen, so the screen has no Cancel row. Each screen is a back-stack entry with the route key
 * ScreenState.Settings(SettingsSubMode.Action(key)); SettingsController shows it.
 *
 * A screen with no action is a message, or a progress screen when its caller changes the info rows ([update]).
 */
object ActionScreens {

    class Action(val label: String, val run: () -> Unit)

    /**
     * [selectAction] false: no row is selected when the screen opens, and the first wheel step selects the first
     * action. Then one centre push cannot do a destructive action by accident.
     * [onCancel] runs when Back closes the screen (not after an action).
     */
    class Spec(
        val title: String,
        val info: List<String> = emptyList(),
        val actions: List<Action> = emptyList(),
        val selectAction: Boolean = true,
        val onCancel: (() -> Unit)? = null
    )

    /** The screens that are kept: an entry under a newer screen can show again when Back comes back to it. */
    const val MAX_SCREENS = 16

    private val specs = LinkedHashMap<Int, Spec>()
    private var nextKey = 1

    /** Keeps [spec] and returns its route key. Only the newest [MAX_SCREENS] are kept. */
    @Synchronized
    fun add(spec: Spec): Int {
        val key = nextKey++
        specs[key] = spec
        while (specs.size > MAX_SCREENS) specs.remove(specs.keys.first())
        return key
    }

    @Synchronized
    fun spec(key: Int): Spec? = specs[key]

    /** Replaces the info rows of screen [key] (a progress screen). Returns false if the screen is gone. */
    @Synchronized
    fun setInfo(key: Int, info: List<String>): Boolean {
        val old = specs[key] ?: return false
        specs[key] = Spec(old.title, info, old.actions, old.selectAction, old.onCancel)
        return true
    }

    @Synchronized
    fun forget(key: Int) {
        specs.remove(key)
    }

    /** Back closes the screen, then runs [Spec.onCancel]. A row closes the screen, then runs its action. */
    fun cancel(spec: Spec?, close: () -> Unit) {
        close()
        spec?.onCancel?.invoke()
    }

    fun choose(action: Action, close: () -> Unit) {
        close()
        action.run()
    }

    fun route(key: Int): ScreenState = ScreenState.Settings(SettingsSubMode.Action(key))

    /** Opens [spec] over the current screen; Back comes back to it. Returns the key of the screen. */
    fun open(spec: Spec): Int {
        val key = add(spec)
        val main = MainActivity.instance ?: return key
        main.runOnUiThread {
            main.wakeFromFakeScreenOff()
            // From Settings, Back comes back into the open group (SettingsController.onEnter)
            if (NavigationManager.getInstance().currentState is ScreenState.Settings) {
                main.backTargetForUtility = MainActivity.STATE_SETTINGS
            }
            main.changeScreen(route(key))
        }
        return key
    }

    /** Shows the new info rows of screen [key] now if it is the screen that shows. */
    fun update(key: Int, info: List<String>) {
        if (!setInfo(key, info)) return
        val main = MainActivity.instance ?: return
        main.runOnUiThread {
            val nav = NavigationManager.getInstance()
            if (nav.currentState == route(key)) nav.activeController?.onRefreshUI()
        }
    }

    /** Closes screen [key] (for example a progress screen at the end of its work): Back, if it shows. */
    fun close(key: Int) {
        forget(key)
        val main = MainActivity.instance ?: return
        main.runOnUiThread {
            val nav = NavigationManager.getInstance()
            if (nav.currentState == route(key)) nav.popBackStack()
        }
    }

    /** A grey info row: not focusable (the wheel skips it), more lines when the text is long. */
    fun infoRow(context: Context, text: String): TextView {
        val d = context.resources.displayMetrics.density
        val classic = ThemeManager.isClassicTheme()
        val padX = ((if (classic) 8 else 12) * d).toInt()
        return TextView(context).apply {
            this.text = text
            setTextColor(ThemeManager.getTextColorSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (classic) 19f else 14f) * d)
            typeface = ThemeManager.getCustomFont()
            setPadding(padX, (6 * d).toInt(), padX, (6 * d).toInt())
            isFocusable = false
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

/** A confirmation: one info line and one action row. Back cancels. */
fun confirmScreen(
    title: String,
    info: String,
    actionLabel: String,
    selectAction: Boolean = true,
    onConfirm: () -> Unit
): ActionScreens.Spec = ActionScreens.Spec(
    title = title,
    info = if (info.isEmpty()) emptyList() else listOf(info),
    actions = listOf(ActionScreens.Action(actionLabel, onConfirm)),
    selectAction = selectAction
)
