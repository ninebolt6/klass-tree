package com.example.api

import com.example.shared.DEFAULT_ID
import com.example.shared.Ids
import com.example.shared.User
import com.example.shared.UserRepository
import com.example.shared.Vector
import com.example.shared.displayName

fun render(user: User): String = user.displayName()

fun main() {
    val repository = UserRepository()
    val service = UserService(repository)
    val user = Ids.emptyUser()

    repository.save(user)
    println(service.greet(DEFAULT_ID))
    println(render(user))

    val names = listOf(user).map(User::displayName)
    println(names.joinToString())

    val sum = Vector(1, 2) + Vector(3, 4)
    println(sum)
}
