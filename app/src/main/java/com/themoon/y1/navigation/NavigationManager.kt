package com.themoon.y1.navigation

import android.view.View
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import java.util.ArrayDeque

/**
 * Coordinates transitions between screen states, manages backstack history,
 * and notifies active ScreenController instances.
 */
class NavigationManager private constructor() {

    private val backStack = ArrayDeque<ScreenState>()
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

    fun registerController(stateClass: Class<out ScreenState>, controller: ScreenController) {
        controllerMap[stateClass] = controller
    }

    fun navigateTo(newState: ScreenState, addToBackStack: Boolean = true) {
        if (newState == currentState && activeController != null) {
            activeController?.onRefreshUI()
            return
        }

        val previous = currentState
        if (newState is ScreenState.MainMenu) {
            backStack.clear()
        } else if (previous != null && addToBackStack) {
            backStack.push(previous)
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
        if (previous != null) {
            transitionListener?.onScreenTransition(previous, newState)
        }
    }

    fun popBackStack(): Boolean {
        if (backStack.isEmpty()) {
            if (currentState != ScreenState.MainMenu) {
                navigateTo(ScreenState.MainMenu, addToBackStack = false)
                return true
            }
            return false
        }

        val previousState = backStack.pop()
        navigateTo(previousState, addToBackStack = false)
        return true
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

    fun reset() {
        backStack.clear()
        currentState = null
        previousScreen = null
        activeController = null
        controllerMap.clear()
        transitionListener = null
    }

    private fun findControllerForState(state: ScreenState): ScreenController? {
        return controllerMap[state.javaClass]
            ?: controllerMap.entries.firstOrNull { it.key.isAssignableFrom(state.javaClass) }?.value
    }

    companion object {
        @Volatile
        private var instance: NavigationManager? = null

        fun getInstance(): NavigationManager {
            return instance ?: synchronized(this) {
                instance ?: NavigationManager().also { instance = it }
            }
        }
    }
}
