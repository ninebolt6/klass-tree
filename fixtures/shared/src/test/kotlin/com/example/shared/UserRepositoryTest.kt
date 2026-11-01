package com.example.shared

class UserRepositoryTest {
    fun savesAndFinds(): Boolean {
        val repository = UserRepository()
        val user = User("42", "Tester")
        repository.save(user)
        return repository.find("42") == user
    }
}
