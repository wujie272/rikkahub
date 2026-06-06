package me.rerere.rikkahub.data.event

import kotlinx.coroutines.CompletableDeferred

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data class RequestLocationPermission(val callback: CompletableDeferred<Boolean>) : AppEvent()
}
