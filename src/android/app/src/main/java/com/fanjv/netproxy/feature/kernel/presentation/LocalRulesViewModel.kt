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

internal data class LocalRuleFileState(
    val document: LocalRulesDocument,
    val revision: String,
)

internal data class LocalRulesUiState(
    val files: Map<LocalRuleKind, LocalRuleFileState> = emptyMap(),
    val modified: Set<LocalRuleKind> = emptySet(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
    val noticeId: Long = 0,
)

internal class LocalRulesViewModel(
    private val repository: ConfigRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalRulesUiState())
    val state: StateFlow<LocalRulesUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "") }
            runCatching {
                LocalRuleKind.entries.associateWith { kind ->
                    val snapshot = repository.readSnapshot(kind.documentId)
                    LocalRuleFileState(
                        document = LocalRulesDocument.parse(snapshot.content),
                        revision = snapshot.revision,
                    )
                }
            }.onSuccess { files ->
                _state.value = LocalRulesUiState(files = files)
            }.onFailure { error ->
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

    fun update(kind: LocalRuleKind, transform: (LocalRulesDocument) -> LocalRulesDocument) {
        _state.update { state ->
            val file = state.files[kind] ?: return@update state
            state.copy(
                files = state.files + (kind to file.copy(document = transform(file.document))),
                modified = state.modified + kind,
                saved = false,
                error = "",
            )
        }
    }

    fun updateRule(
        kind: LocalRuleKind,
        index: Int,
        transform: (LocalRuleDraft) -> LocalRuleDraft,
    ) = update(kind) { document ->
        document.copy(rules = document.rules.mapIndexed { current, rule ->
            if (current == index) transform(rule) else rule
        })
    }

    fun addRule(kind: LocalRuleKind) = update(kind) { document ->
        document.copy(rules = document.rules + LocalRuleDraft.empty())
    }

    fun removeRule(kind: LocalRuleKind, index: Int) = update(kind) { document ->
        document.copy(rules = document.rules.filterIndexed { current, _ -> current != index })
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.saving || snapshot.modified.isEmpty()) return
        val validationError = snapshot.modified.firstNotNullOfOrNull { kind ->
            snapshot.files[kind]?.document?.validate()
        }
        if (validationError != null) {
            _state.update { it.copy(error = validationError, noticeId = it.noticeId + 1) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saved = false, error = "") }
            var files = snapshot.files
            var modified = snapshot.modified
            runCatching {
                LocalRuleKind.entries.filter(modified::contains).forEach { kind ->
                    val file = files.getValue(kind)
                    val revision = repository.apply(
                        kind.documentId,
                        file.document.encode(),
                        file.revision,
                    )
                    files = files + (kind to file.copy(revision = revision))
                    modified = modified - kind
                    _state.update { it.copy(files = files, modified = modified) }
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        files = files,
                        modified = modified,
                        saving = false,
                        saved = true,
                        noticeId = it.noticeId + 1,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        files = files,
                        modified = modified,
                        saving = false,
                        error = error.userMessage(),
                        noticeId = it.noticeId + 1,
                    )
                }
            }
        }
    }

    fun clearNotice() = _state.update { it.copy(saved = false, error = "") }
}
