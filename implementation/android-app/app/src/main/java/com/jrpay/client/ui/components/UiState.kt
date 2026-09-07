package com.jrpay.client.ui.components

/** Standard tri-state holder for feature screens. */
sealed interface UiState<out T> {
    object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
