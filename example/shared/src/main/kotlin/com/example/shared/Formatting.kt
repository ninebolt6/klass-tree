package com.example.shared

typealias UserId = String

const val DEFAULT_ID: UserId = "anonymous"

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SharedApi

object Ids {
    fun next(): String = "generated"

    fun emptyUser(): User = User(next(), "nobody")
}

fun User.displayName(): String = name.uppercase()

fun defaultUser(): User = User(DEFAULT_ID, "Anonymous")
