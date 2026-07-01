package com.aleksander.proxy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    var type by mutableStateOf(ProxyType.MTPROTO)
        private set

    var loading by mutableStateOf(false)
        private set

    var proxies by mutableStateOf<List<Proxy>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** True once the user has run at least one search (controls empty-state text). */
    var searched by mutableStateOf(false)
        private set

    fun selectType(t: ProxyType) {
        if (!loading) type = t
    }

    fun find() {
        if (loading) return
        loading = true
        error = null
        searched = true
        proxies = emptyList()
        viewModelScope.launch {
            try {
                val result = ProxyRepository.findWorking(type, want = 10)
                proxies = result
                if (result.isEmpty()) {
                    error = "Не удалось получить прокси. Проверьте интернет и попробуйте снова."
                }
            } catch (e: Exception) {
                error = "Ошибка: ${e.message ?: "неизвестная ошибка сети"}"
            } finally {
                loading = false
            }
        }
    }
}
