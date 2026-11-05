package com.themoon.y1.navigation

import android.view.View
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.views.ListRows
import java.util.ArrayDeque

/**
 * Coordinates transitions between screen states, manages backstack history,
 * and notifies active ScreenController instances.
 *
 * The back stack is the one record of the way back, as on the iPod: each list that opens going down (a screen,
 * a Music level, a Settings group or row screen) is a [ScreenState]. The list that it leaves is kept as an entry
 * with its selected row; Back takes the top entry, the controller builds that list again from the state, and the
 * row is selected again at the same height. The main menu is the bottom entry.
 */
class NavigationManager private constructor() {

    /** A list to come back to (its route key), and its selected row when the deeper list opened. */
    data class BackEntry(val state: ScreenState, val row: RowKey?)

    private val backStack = ArrayDeque<BackEntry>()
    private val controllerMap = HashMap<Class<out ScreenState>, ScreenController>()

    var currentState: ScreenState? = null
        private set

    /** The screen that the last change left (the screen before [currentState]), or null. */
    var previousScreen: ScreenState? = null
        private set

    var activeController: ScreenController? = null

    interface StateChangeListener {
        fun onScreenTransition(from: ScreenState, to: ScreenState)
    }

    private var transitionListener: StateChangeListener? = null

    fun setTransitionListener(listener: StateChangeListener?) {
        this.transitionListener = listener
    }

    /** The direction of a screen change: one list deeper (PUSH), back up (POP), or in place (NONE). */
    enum class Direction { PUSH, POP, NONE }

    /**
     * Told before the old screen exits and after the new screen entered, with the direction of the change
     * (for example to slide the lists as on the iPod). Not told when the same state only refreshes.
     */
    interface DirectionListener {
        fun onBeforeTransition(from: ScreenState?, to: ScreenState, direction: Direction) {}
        fun onAfterTransition(from: ScreenState?, to: ScreenState, direction: Direction) {}
    }

    private val directionListeners = ArrayList<DirectionListener>()

    fun addDirectionListener(listener: DirectionListener) {
        if (listener !in directionListeners) directionListeners.add(listener)
    }

    fun removeDirectionListener(listener: DirectionListener) {
        directionListeners.remove(listener)
    }

    fun registerController(stateClass: Class<out ScreenState>, controller: ScreenController) {
        controllerMap[stateClass] = controller
    }

    /** Opens [newState]. [row]: the row to select in it in place of its first row (a folder list that opens going up). */
    fun navigateTo(newState: ScreenState, addToBackStack: Boolean = true, row: RowKey? = null) {
        navigate(newState, addToBackStack, row, pop = false)
    }

    /**
     * Back rule (as on the iPod): a list that opens going down selects its first row; Back selects again the
     * row that opened the deeper list. [returnRow] is that row when Back comes to [newState].
     * [pop] = the change comes from the back stack (Back).
     */
    private fun navigate(newState: ScreenState, addToBackStack: Boolean, returnRow: RowKey?, pop: Boolean) {
        if (newState == currentState && activeController != null) {
            activeController?.onRefreshUI()
            return
        }

        ListRows.cancelPending()
        val previous = currentState
        val direction = directionOf(previous, newState, addToBackStack, pop)
        for (l in directionListeners.toTypedArray()) l.onBeforeTransition(previous, newState, direction)
        var back = returnRow
        if (newState is ScreenState.MainMenu) {
            // The bottom entry: going to the main menu (with Back or directly) takes all entries off
            if (back == null) back = backStack.lastOrNull { it.state is ScreenState.MainMenu }?.row
            backStack.clear()
        } else if (previous != null && addToBackStack) {
            backStack.push(BackEntry(previous, activeController?.selectedRow()))
        }

        activeController?.onExit()

        previousScreen = previous
        currentState = newState
        val controller = findControllerForState(newState)
        activeController = controller

        val targetRoot = controller?.rootView
        val handledRoots = HashSet<View>()
        if (targetRoot != null) {
            targetRoot.visibility = View.VISIBLE
            handledRoots.add(targetRoot)
        }
        controllerMap.values.forEach { ctrl ->
            if (ctrl.rootView !in handledRoots && ctrl.rootView.parent != null) {
                ctrl.rootView.visibility = View.GONE
                handledRoots.add(ctrl.rootView)
            }
        }

        controller?.onEnter(newState)
        if (back != null) controller?.selectRow(back)
        if (previous != null) {
            transitionListener?.onScreenTransition(previous, newState)
        }
        for (l in directionListeners.toTypedArray()) l.onAfterTransition(previous, newState, direction)
    }

    fun popBackStack(): Boolean {
        if (backStack.isEmpty()) {
            if (currentState != ScreenState.MainMenu) {
                navigate(ScreenState.MainMenu, addToBackStack = false, returnRow = null, pop = true)
                return true
            }
            return false
        }

        val entry = backStack.pop()
        navigate(entry.state, addToBackStack = false, returnRow = entry.row, pop = true)
        return true
    }

    /** The state of the top back-stack entry (where Back goes), or null. */
    fun previousState(): ScreenState? = backStack.peek()?.state

    /** Back to the nearest entry that [where] accepts; the entries above it go. Without one: to the main menu. */
    fun popBackStackTo(where: (ScreenState) -> Boolean): Boolean {
        while (backStack.isNotEmpty() && !where(backStack.peek().state)) backStack.pop()
        return popBackStack()
    }

    fun navigateBack(): Boolean {
        if (activeController?.onBackPressed() == true) {
            return true
        }

        return popBackStack()
    }

    fun clearBackStack() {
        backStack.clear()
    }

    /**
     * Makes [state] the entry under the current screen, where Back goes: the entries above the newest entry of
     * [state] go; without one, [state] is added with no row. For a screen that opens directly but has its menu
     * under it (the FM tuner and its Radio menu, as on the iPod nano).
     */
    fun setBackTarget(state: ScreenState) {
        if (backStack.any { it.state == state }) {
            while (backStack.peek().state != state) backStack.pop()
        } else {
            backStack.push(BackEntry(state, null))
        }
    }

    fun reset() {
        backStack.clear()
        currentState = null
        previousScreen = null
        activeController = null
        controllerMap.clear()
        transitionListener = null
        directionListeners.clear()
    }

    private fun findControllerForState(state: ScreenState): ScreenController? {
        return controllerMap[state.javaClass]
            ?: controllerMap.entries.firstOrNull { it.key.isAssignableFrom(state.javaClass) }?.value
    }

    companion object {
        /**
         * Back (or a change to the main menu, the bottom entry) goes up; a change that keeps the old list on the
         * back stack goes one list deeper; a change that replaces the list (no back-stack entry) stays in place.
         */
        fun directionOf(from: ScreenState?, to: ScreenState, addToBackStack: Boolean, pop: Boolean): Direction = when {
            from == null -> Direction.NONE
            pop || to is ScreenState.MainMenu -> Direction.POP
            addToBackStack -> Direction.PUSH
            else -> Direction.NONE
        }

        @Volatile
        private var instance: NavigationManager? = null

        fun getInstance(): NavigationManager {
            return instance ?: synchronized(this) {
                instance ?: NavigationManager().also { instance = it }
            }
        }
    }
}
