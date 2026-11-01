package com.example.shared

class UserRepository : Repository<User> {
    private val users: MutableMap<String, User> = mutableMapOf()

    override fun find(id: String): User? = users[id]

    override fun save(entity: User) {
        users[entity.id] = entity
    }
}
