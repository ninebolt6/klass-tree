package com.example.shared

interface Repository<T : Entity> {
    fun find(id: String): T?

    fun save(entity: T)
}
