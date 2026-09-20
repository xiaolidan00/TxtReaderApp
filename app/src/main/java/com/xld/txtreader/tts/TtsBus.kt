package com.xld.txtreader.tts

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class TtsUiState(
    val isPlaying: Boolean = false,
    val available: Boolean = false,
    val chapter: Int = 0,
    val pageInChapter: Int = 0,
    val totalChapter: Int = 0,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val speed: Float = 1f,
) {
    val progressText: String
        get() = if (totalChapter > 0) "${chapter + 1}/${totalChapter}" else "0/0"
}

object TtsBus {
    private val _state = MutableStateFlow(TtsUiState())
    val state: StateFlow<TtsUiState> = _state.asStateFlow()

    fun update(transform: (TtsUiState) -> TtsUiState) {
        _state.value = transform(_state.value)
    }
}