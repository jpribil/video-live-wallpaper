package io.github.codeg0blin.videowallpaper

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs file imports (copying into private storage) outside the activity, so
 * a rotation or other configuration change mid-copy doesn't cancel it.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    sealed class State {
        data object Idle : State()

        /** [progress] is 0..1, or negative while the size is unknown. */
        data class Copying(val progress: Float) : State()

        data class Failed(val reason: MediaLibrary.Reason) : State()
    }

    private val library = MediaLibrary(app)
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Imports a file the user just picked and, on success, makes it the wallpaper. */
    fun import(uri: Uri) {
        if (_state.value is State.Copying) return
        _state.value = State.Copying(-1f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                library.import(uri) { p -> _state.value = State.Copying(p) }
            }
            when (result) {
                is MediaLibrary.Result.Success -> {
                    store.setMedia(result.media, result.displayName, result.durationMs)
                    withContext(Dispatchers.IO) {
                        library.deleteAllExcept(result.media.uri)
                        library.releaseAllPersistedPermissions()
                    }
                    _state.value = State.Idle
                }
                is MediaLibrary.Result.Failure -> {
                    withContext(Dispatchers.IO) { releasePermission(uri) }
                    _state.value = State.Failed(result.reason)
                }
            }
        }
    }

    /** Removes files from interrupted imports. */
    fun cleanUp() {
        val keep = store.load().media?.uri
        viewModelScope.launch(Dispatchers.IO) {
            if (_state.value !is State.Copying) library.deleteAllExcept(keep)
        }
    }

    fun acknowledgeFailure() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private fun releasePermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Nothing held.
        }
    }
}
