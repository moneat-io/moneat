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
            user = UserResponse(userId, request.email, request.name, emailVerified, false, false)
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
            
            if (!user[Users.email_verified]) {
                throw IllegalArgumentException("Email not verified. Please check your email for the verification link.")
            }
            
            val userId = user[Users.id]
            val token = generateToken(userId, user[Users.email])
            AuthResponse(
                token = token,
                user = UserResponse(
                    userId, 
                    user[Users.email], 
                    user[Users.name],
                    user[Users.email_verified],
                    user[Users.onboarding_completed],
                    user[Users.is_admin]
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
    
    fun requestPasswordReset(email: String): Boolean {
        return transaction {
            val user = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
                ?: return@transaction false
            
            // Generate reset token
            val resetToken = generateVerificationToken()
            val expiresAt = System.currentTimeMillis() + (60 * 60 * 1000) // 1 hour
            
            Users.update({ Users.id eq user[Users.id] }) {
                it[password_reset_token] = resetToken
                it[password_reset_expires_at] = expiresAt
            }
            
            // Send password reset email
            try {
                emailService.sendPasswordResetEmail(email, resetToken, user[Users.name])
                true
            } catch (e: Exception) {
                println("Failed to send password reset email: ${e.message}")
                false
            }
        }
    }
    
    fun resetPassword(token: String, newPassword: String): Boolean {
        if (newPassword.length < 8) {
            throw IllegalArgumentException("Password must be at least 8 characters")
        }
        
        return transaction {
            val user = Users.selectAll()
                .where { Users.password_reset_token eq token }
                .firstOrNull()
                ?: return@transaction false
            
            val expiresAt = user[Users.password_reset_expires_at]
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                return@transaction false
            }
            
            // Update password and clear reset token
            val passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            Users.update({ Users.id eq user[Users.id] }) {
                it[password_hash] = passwordHash
                it[password_reset_token] = null
                it[password_reset_expires_at] = null
            }
            
            true
        }
    }
    
    fun completeOnboarding(userId: Int, organizationName: String, companySize: String): UserResponse {
        return transaction {
            val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?: throw IllegalArgumentException("User not found")
            
            // Get the user's default organization
            val membership = Memberships.selectAll()
                .where { Memberships.user_id eq userId }
                .firstOrNull()
                ?: throw IllegalArgumentException("No organization found for user")
            
            val orgId = membership[Memberships.organization_id]
            
            // Update organization with new name and company size
            val slug = organizationName.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(100)
            
            Organizations.update({ Organizations.id eq orgId }) {
                it[name] = organizationName
                it[Organizations.slug] = slug
                it[company_size] = companySize
            }
            
            // Mark onboarding as completed
            Users.update({ Users.id eq userId }) {
                it[onboarding_completed] = true
            }
            
            UserResponse(
                user[Users.id],
                user[Users.email],
                user[Users.name],
                user[Users.email_verified],
                true,
                user[Users.is_admin]
            )
        }
    }
}
