package com.example.shared

interface Entity {
    val id: String
}

data class User(
    override val id: String,
    val name: String,
) : Entity
