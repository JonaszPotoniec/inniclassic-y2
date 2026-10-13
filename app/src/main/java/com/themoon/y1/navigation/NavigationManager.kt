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
        if (previous != null && addToBackStack) {
            backStack.push(previous)
        }

        activeController?.onExit()

        currentState = newState
        val controller = findControllerForState(newState)
        activeController = controller

        controllerMap.values.forEach { ctrl ->
            if (ctrl == controller) {
                ctrl.rootView.visibility = View.VISIBLE
            } else if (ctrl.rootView.parent != null) {
                ctrl.rootView.visibility = View.GONE
            }
        }

        controller?.onEnter(newState)
        if (previous != null) {
            transitionListener?.onScreenTransition(previous, newState)
        }
    }

    fun navigateBack(): Boolean {
        if (activeController?.onBackPressed() == true) {
            return true
        }

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

    fun clearBackStack() {
        backStack.clear()
    }

    fun reset() {
        backStack.clear()
        currentState = null
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
