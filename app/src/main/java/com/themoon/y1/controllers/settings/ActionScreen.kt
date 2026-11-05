package com.themoon.y1.controllers.settings

import com.themoon.y1.MainActivity
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode

/**
 * An iPod-style list screen in place of an Android dialog: the title in the status bar, grey info rows (the wheel
 * skips them) and one row for each action, shown as a list of the list model ([list]). The centre button does the
 * action of the selected row; Back (Menu) closes the screen, so the screen has no Cancel row. Each screen is a
 * back-stack entry with the route key ScreenState.Settings(SettingsSubMode.Action(key)); SettingsController shows it.
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

    /**
     * [spec] as a list ([ListSpec]): an info row ([RowLook.INFO]: the wheel skips it) for each info line, then a row
     * for each action. A row closes the screen ([close]), then does its action ([choose]).
     */
    fun list(spec: Spec, close: () -> Unit): ListSpec = ListSpec(
        spec.title,
        spec.info.map { RowSpec(it, RowLook.INFO) } + spec.actions.map { action ->
            RowSpec(action.label, RowLook.VALUE, value = "", onClick = { choose(action, close) })
        },
        focus = if (spec.selectAction) Focus.FIRST else Focus.NONE
    )
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
