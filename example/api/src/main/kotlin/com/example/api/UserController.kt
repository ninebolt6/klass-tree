package com.example.api

import com.example.shared.SharedApi
import com.example.shared.User
import com.example.shared.UserRepository

@SharedApi
class UserController(
    private val service: UserService,
    private val repository: UserRepository,
) {
    fun get(id: String): User? {
        service.greet(id)
        return repository.find(id)
    }
}
