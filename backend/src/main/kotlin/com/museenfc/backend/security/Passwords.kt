package com.museenfc.backend.security

import org.mindrot.jbcrypt.BCrypt

object Passwords {
    fun hash(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt())
    fun matches(plain: String, hash: String): Boolean = BCrypt.checkpw(plain, hash)
}
