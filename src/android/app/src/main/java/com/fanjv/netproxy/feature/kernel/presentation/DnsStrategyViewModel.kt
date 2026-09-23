package com.fanjv.netproxy.feature.kernel.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanjv.netproxy.core.ui.userMessage
import com.fanjv.netproxy.feature.settings.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DnsStrategyState(
    val document: DnsStrategyDocument? = null,
    val revision: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
    val noticeId: Long = 0,
)

internal class DnsStrategyViewModel(private val repository: ConfigRepository) : ViewModel() {
    private val _state = MutableStateFlow(DnsStrategyState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "") }
            runCatching { repository.readSnapshot(CONFIG_DOCUMENT) }
                .mapCatching { snapshot -> DnsStrategyDocument.parse(snapshot.content) to snapshot.revision }
                .onSuccess { (document, revision) ->
                    _state.value = DnsStrategyState(document = document, revision = revision)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = error.userMessage(), noticeId = it.noticeId + 1)
                    }
                }
        }
    }

    fun update(transform: (DnsStrategyDocument) -> DnsStrategyDocument) {
        _state.update { state ->
            state.document?.let { state.copy(document = transform(it), saved = false, error = "") } ?: state
        }
    }

    fun save() {
        val state = _state.value
        val document = state.document ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saved = false, error = "") }
            runCatching { repository.apply(CONFIG_DOCUMENT, document.encode(), state.revision) }
                .onSuccess { revision ->
                    _state.update {
                        it.copy(revision = revision, saving = false, saved = true, noticeId = it.noticeId + 1)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(saving = false, error = error.userMessage(), noticeId = it.noticeId + 1)
                    }
                }
        }
    }

    fun clearNotice() = _state.update { it.copy(saved = false, error = "") }

    private companion object {
        const val CONFIG_DOCUMENT = "singbox/config.json"
    }
}
