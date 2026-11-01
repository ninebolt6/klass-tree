package com.example.shared

data class Vector(val x: Int, val y: Int) {
    operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
}
