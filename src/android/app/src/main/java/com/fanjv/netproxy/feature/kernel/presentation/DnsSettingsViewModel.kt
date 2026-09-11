package com.fanjv.netproxy.feature.kernel.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanjv.netproxy.core.ui.userMessage
import com.fanjv.netproxy.feature.settings.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DnsSettingsUiState(
    val document: DnsSettingsDocument? = null,
    val revision: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
    val noticeId: Long = 0,
)

internal class DnsSettingsViewModel(
    private val repository: ConfigRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DnsSettingsUiState())
    val state: StateFlow<DnsSettingsUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "") }
            runCatching { repository.readSnapshot(DNS_DOCUMENT) }
                .onSuccess { snapshot ->
                    runCatching { DnsSettingsDocument.parse(snapshot.content) }
                        .onSuccess { document ->
                            _state.value = DnsSettingsUiState(
                                document = document,
                                revision = snapshot.revision,
                            )
                        }
                        .onFailure { error ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    error = error.userMessage(),
                                    noticeId = it.noticeId + 1,
                                )
                            }
                        }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.userMessage(),
                            noticeId = it.noticeId + 1,
                        )
                    }
                }
        }
    }

    fun update(transform: (DnsSettingsDocument) -> DnsSettingsDocument) {
        _state.update { state ->
            state.document?.let { state.copy(document = transform(it), saved = false, error = "") } ?: state
        }
    }

    fun updateServer(index: Int, transform: (DnsServerDraft) -> DnsServerDraft) {
        update { document ->
            document.copy(servers = document.servers.mapIndexed { current, server ->
                if (current == index) transform(server) else server
            })
        }
    }

    fun addServer() = update { it.copy(servers = it.servers + DnsServerDraft.empty()) }

    fun removeServer(index: Int) = update { document ->
        document.copy(servers = document.servers.filterIndexed { current, _ -> current != index })
    }

    fun save() {
        val state = _state.value
        val document = state.document ?: return
        val validationError = document.validate()
        if (validationError != null) {
            _state.update { it.copy(error = validationError, noticeId = it.noticeId + 1) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saved = false, error = "") }
            runCatching { repository.apply(DNS_DOCUMENT, document.encode(), state.revision) }
                .onSuccess { revision ->
                    _state.update {
                        it.copy(
                            revision = revision,
                            saving = false,
                            saved = true,
                            noticeId = it.noticeId + 1,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            saving = false,
                            error = error.userMessage(),
                            noticeId = it.noticeId + 1,
                        )
                    }
                }
        }
    }

    fun clearNotice() = _state.update { it.copy(saved = false, error = "") }

    private companion object {
        const val DNS_DOCUMENT = "singbox/dns"
    }
}
