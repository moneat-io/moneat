package com.moneat.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.*
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.*

class AuthService {
    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    private val emailService = EmailService()
    private val secureRandom = SecureRandom()
    
    fun signup(request: SignupRequest): AuthResponse {
        if (request.email.isBlank() || request.password.length < 8) {
            throw IllegalArgumentException("Invalid email or password too short")
        }
        
        val (userId, emailVerified) = transaction {
            // Check if user exists
            val existing = Users.selectAll().where { Users.email eq request.email }.firstOrNull()
            if (existing != null) {
                throw IllegalArgumentException("User already exists")
            }
            
            // Generate verification token
            val verificationToken = generateVerificationToken()
            val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours
            
            // Create user
            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
            val id = Users.insert {
                it[email] = request.email
                it[password_hash] = passwordHash
                it[name] = request.name
                it[email_verified] = false
                it[email_verification_token] = verificationToken
                it[email_verification_expires_at] = expiresAt
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
            
            // Send verification email
            try {
                emailService.sendVerificationEmail(request.email, verificationToken, request.name)
            } catch (e: Exception) {
                // Log but don't fail signup if email fails
                println("Failed to send verification email: ${e.message}")
            }
            
            id to false
        }
        
        val token = generateToken(userId, request.email)
        return AuthResponse(
            token = token,
            user = UserResponse(userId, request.email, request.name, emailVerified)
        )
    }
    
    fun verifyEmail(token: String): Boolean {
        return transaction {
            val user = Users.selectAll()
                .where { Users.email_verification_token eq token }
                .firstOrNull()
                ?: return@transaction false
            
            val expiresAt = user[Users.email_verification_expires_at]
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                return@transaction false
            }
            
            // Mark as verified and clear token
            Users.update({ Users.id eq user[Users.id] }) {
                it[email_verified] = true
                it[email_verification_token] = null
                it[email_verification_expires_at] = null
            }
            
            true
        }
    }
    
    fun resendVerificationEmail(email: String): Boolean {
        return transaction {
            val user = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
                ?: return@transaction false
            
            // Check if already verified
            if (user[Users.email_verified]) {
                throw IllegalArgumentException("Email already verified")
            }
            
            // Generate new token
            val verificationToken = generateVerificationToken()
            val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
            
            Users.update({ Users.id eq user[Users.id] }) {
                it[email_verification_token] = verificationToken
                it[email_verification_expires_at] = expiresAt
            }
            
            // Send email
            try {
                emailService.sendVerificationEmail(email, verificationToken, user[Users.name])
                true
            } catch (e: Exception) {
                println("Failed to send verification email: ${e.message}")
                false
            }
        }
    }
    
    fun login(request: LoginRequest): AuthResponse? {
        return transaction {
            val user = Users.selectAll().where { Users.email eq request.email }.firstOrNull()
                ?: return@transaction null
            
            if (!BCrypt.checkpw(request.password, user[Users.password_hash])) {
                return@transaction null
            }
            
            val userId = user[Users.id]
            val token = generateToken(userId, user[Users.email])
            AuthResponse(
                token = token,
                user = UserResponse(
                    userId, 
                    user[Users.email], 
                    user[Users.name],
                    user[Users.email_verified]
                )
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
    
    private fun generateVerificationToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
