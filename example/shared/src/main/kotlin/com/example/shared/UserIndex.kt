package com.example.shared

class UserIndex {
    private val byName: Map<String, List<User>> = emptyMap()

    fun usersNamed(name: String): List<User> = byName[name].orEmpty()
}
