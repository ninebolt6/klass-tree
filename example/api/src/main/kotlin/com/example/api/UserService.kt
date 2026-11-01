package com.example.api

import com.example.shared.Repository
import com.example.shared.User
import com.example.shared.displayName

class UserService(private val repository: Repository<User>) {
    fun greet(id: String): String {
        val user = repository.find(id) ?: return "unknown"
        return user.displayName()
    }
}
