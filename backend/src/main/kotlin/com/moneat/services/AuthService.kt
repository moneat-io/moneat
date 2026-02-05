package com.moneat.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.*
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.*

class AuthService {
    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    
    fun signup(request: SignupRequest): AuthResponse {
        if (request.email.isBlank() || request.password.length < 8) {
            throw IllegalArgumentException("Invalid email or password too short")
        }
        
        val userId = transaction {
            // Check if user exists
            val existing = Users.select { Users.email eq request.email }.firstOrNull()
            if (existing != null) {
                throw IllegalArgumentException("User already exists")
            }
            
            // Create user
            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
            val id = Users.insert {
                it[email] = request.email
                it[password_hash] = passwordHash
                it[name] = request.name
            }[Users.id]
            
            // Create default organization
            val orgId = Organizations.insert {
                it[name] = "${request.name ?: request.email}'s Organization"
                it[slug] = "org-${UUID.randomUUID().toString().take(8)}"
            }[Organizations.id]
            
            // Add membership
            Memberships.insert {
                it[user_id] = id
                it[organization_id] = orgId
                it[role] = "owner"
            }
            
            id
        }
        
        val token = generateToken(userId, request.email)
        return AuthResponse(
            token = token,
            user = UserResponse(userId, request.email, request.name)
        )
    }
    
    fun login(request: LoginRequest): AuthResponse? {
        return transaction {
            val user = Users.select { Users.email eq request.email }.firstOrNull()
                ?: return@transaction null
            
            if (!BCrypt.checkpw(request.password, user[Users.password_hash])) {
                return@transaction null
            }
            
            val userId = user[Users.id]
            val token = generateToken(userId, user[Users.email])
            AuthResponse(
                token = token,
                user = UserResponse(userId, user[Users.email], user[Users.name])
            )
        }
    }
    
    private fun generateToken(userId: Int, email: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255)
    val password_hash = varchar("password_hash", 255)
    val name = varchar("name", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Organizations : Table("organizations") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    override val primaryKey = PrimaryKey(id)
}

object Memberships : Table("memberships") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val role = varchar("role", 50)
    override val primaryKey = PrimaryKey(id)
}
