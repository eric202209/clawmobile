package com.user.service

object ProviderRegistry {
    private val providers = mutableListOf<AiProvider>()

    fun register(provider: AiProvider) {
        providers.removeAll { it.id == provider.id }
        providers.add(provider)
    }

    fun unregister(id: String) {
        providers.removeAll { it.id == id }
    }

    fun getAll(): List<AiProvider> = providers.toList()

    fun getById(id: String): AiProvider? = providers.find { it.id == id }
}
