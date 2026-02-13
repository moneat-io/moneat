package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.models.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

class AccountDeletionService(
    private val stripeService: StripeService = StripeService(),
    private val emailService: EmailService = EmailService()
) {
    
    data class DeletionValidationResult(
        val canDelete: Boolean,
        val errorMessage: String? = null,
        val organizationsAsLastOwner: List<String> = emptyList()
    )
    
    /**
     * Validates if a user can delete their account
     * Users cannot delete if they are the last owner of any organization
     */
    fun validateUserDeletion(userId: Int): DeletionValidationResult {
        return transaction {
            // Find all organizations where this user is an owner
            val ownedOrgs = Memberships.innerJoin(Organizations)
                .selectAll()
                .where { 
                    (Memberships.user_id eq userId) and 
                    (Memberships.role eq "owner") and
                    (Organizations.deletedAt.isNull())
                }
                .map { row ->
                    val orgId = row[Memberships.organization_id]
                    val orgName = row[Organizations.name]
                    
                    // Count total owners for this org
                    val ownerCount = Memberships.selectAll()
                        .where { 
                            (Memberships.organization_id eq orgId) and 
                            (Memberships.role eq "owner")
                        }
                        .count()
                    
                    Pair(orgName, ownerCount)
                }
                .filter { (_, ownerCount) -> ownerCount == 1L }
                .map { (orgName, _) -> orgName }
            
            if (ownedOrgs.isNotEmpty()) {
                DeletionValidationResult(
                    canDelete = false,
                    errorMessage = "You are the last owner of ${ownedOrgs.size} organization(s). You must delete these organizations or transfer ownership before deleting your account.",
                    organizationsAsLastOwner = ownedOrgs
                )
            } else {
                DeletionValidationResult(canDelete = true)
            }
        }
    }
    
    /**
     * Validates if an organization can be deleted
     * Checks if user is an owner and if there's an active subscription
     */
    fun validateOrganizationDeletion(organizationId: Int, userId: Int): DeletionValidationResult {
        return transaction {
            // Check if user is an owner
            val membership = Memberships.selectAll()
                .where { 
                    (Memberships.user_id eq userId) and 
                    (Memberships.organization_id eq organizationId)
                }
                .singleOrNull()
            
            if (membership == null || membership[Memberships.role] != "owner") {
                return@transaction DeletionValidationResult(
                    canDelete = false,
                    errorMessage = "Only organization owners can delete the organization"
                )
            }
            
            // Check for active subscription
            val activeSubscription = Subscriptions.selectAll()
                .where {
                    (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing"))
                }
                .singleOrNull()
            
            if (activeSubscription != null) {
                return@transaction DeletionValidationResult(
                    canDelete = false,
                    errorMessage = "Please cancel your active subscription before deleting the organization"
                )
            }
            
            DeletionValidationResult(canDelete = true)
        }
    }
    
    /**
     * Deletes a user account
     * - Removes user from all organizations
     * - Soft deletes user record
     * - Revokes any pending invitations sent by the user
     */
    suspend fun deleteUserAccount(userId: Int): Boolean {
        try {
            // First validate
            val validation = validateUserDeletion(userId)
            if (!validation.canDelete) {
                logger.warn { "Cannot delete user $userId: ${validation.errorMessage}" }
                return false
            }
            
            transaction {
                val user = Users.selectAll()
                    .where { Users.id eq userId }
                    .singleOrNull() ?: return@transaction
                
                val email = user[Users.email]
                
                // Remove user from all organizations (CASCADE will handle this via FK)
                Memberships.deleteWhere { Memberships.user_id eq userId }
                
                // Revoke pending invitations sent by this user
                OrgInvitations.update({ (OrgInvitations.invited_by eq userId) and (OrgInvitations.status eq "pending") }) {
                    it[status] = "revoked"
                }
                
                // Soft delete user
                Users.update({ Users.id eq userId }) {
                    it[deletedAt] = CurrentTimestamp()
                }
                
                logger.info { "Soft deleted user account: $userId ($email)" }
            }
            
            // Send confirmation email (async)
            try {
                val userEmail = transaction {
                    Users.selectAll()
                        .where { Users.id eq userId }
                        .singleOrNull()?.get(Users.email)
                }
                if (userEmail != null) {
                    emailService.sendAccountDeletionConfirmation(userEmail)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to send account deletion confirmation email" }
            }
            
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete user account $userId" }
            return false
        }
    }
    
    /**
     * Deletes an organization and all associated data
     * - Cancels active subscriptions
     * - Deletes all projects and associated events from ClickHouse
     * - Removes all members
     * - Revokes pending invitations
     * - Soft deletes organization
     */
    suspend fun deleteOrganization(organizationId: Int, deletedByUserId: Int): Boolean {
        try {
            // First validate
            val validation = validateOrganizationDeletion(organizationId, deletedByUserId)
            if (!validation.canDelete) {
                logger.warn { "Cannot delete organization $organizationId: ${validation.errorMessage}" }
                return false
            }
            
            val orgData = transaction {
                val org = Organizations.selectAll()
                    .where { Organizations.id eq organizationId }
                    .singleOrNull() ?: return@transaction null
                
                val projects = Projects.selectAll()
                    .where { Projects.organization_id eq organizationId }
                    .map { it[Projects.id].toInt() }
                
                Triple(org[Organizations.name], org[Organizations.slug], projects)
            } ?: return false
            
            val (orgName, _, projectIds) = orgData
            val memberEmails = transaction {
                Users.innerJoin(Memberships)
                    .selectAll()
                    .where { Memberships.organization_id eq organizationId }
                    .map { it[Users.email] }
            }
            
            logger.info { "Starting deletion of organization $organizationId ($orgName) with ${projectIds.size} projects" }
            
            // Delete ClickHouse data for all projects
            if (projectIds.isNotEmpty()) {
                deleteClickHouseDataForProjects(projectIds)
            }
            
            // Cancel Stripe subscription if exists
            if (stripeService.isStripeEnabled()) {
                try {
                    transaction {
                        val subscription = Subscriptions.selectAll()
                            .where { Subscriptions.organization_id eq organizationId }
                            .singleOrNull()
                        
                        subscription?.get(Subscriptions.stripe_subscription_id)?.let { stripeSubId ->
                            stripeService.cancelSubscription(stripeSubId)
                            logger.info { "Cancelled Stripe subscription $stripeSubId for org $organizationId" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to cancel Stripe subscription for org $organizationId" }
                    // Continue with deletion even if Stripe fails
                }
            }
            
            // PostgreSQL cleanup (most handled by CASCADE)
            transaction {
                // Revoke pending invitations
                OrgInvitations.update({ 
                    (OrgInvitations.organization_id eq organizationId) and 
                    (OrgInvitations.status eq "pending") 
                }) {
                    it[status] = "revoked"
                }
                
                // Delete usage records
                UsageRecords.deleteWhere { UsageRecords.organization_id eq organizationId }

                // Revoke access to deleted organization by removing all memberships.
                Memberships.deleteWhere { Memberships.organization_id eq organizationId }
                
                // Soft delete organization
                Organizations.update({ Organizations.id eq organizationId }) {
                    it[deletedAt] = CurrentTimestamp()
                    it[deletedBy] = deletedByUserId
                }
                
                logger.info { "Soft deleted organization: $organizationId ($orgName)" }
            }
            
            // Send confirmation emails to all members
            try {
                memberEmails.forEach { email ->
                    try {
                        emailService.sendOrganizationDeletionNotification(email, orgName)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to send deletion notification to $email" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to send organization deletion notifications" }
            }
            
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete organization $organizationId" }
            return false
        }
    }
    
    /**
     * Deletes all ClickHouse data for given project IDs
     */
    private suspend fun deleteClickHouseDataForProjects(projectIds: List<Int>) = withContext(Dispatchers.IO) {
        try {
            val projectIdsList = projectIds.joinToString(",")
            
            // Delete from all ClickHouse tables
            val tables = listOf(
                "events",
                "spans", 
                "sessions",
                "replay_events",
                "replay_segments",
                "user_feedback",
                "logs"
            )
            
            tables.forEach { table ->
                try {
                    val query = "ALTER TABLE $table DELETE WHERE project_id IN ($projectIdsList)"
                    val response = ClickHouseClient.execute(query)
                    
                    if (response.status == HttpStatusCode.OK) {
                        logger.info { "Deleted ClickHouse data from $table for ${projectIds.size} projects" }
                    } else {
                        logger.warn { "ClickHouse deletion from $table returned status ${response.status}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete ClickHouse data from $table" }
                }
            }
            
            // Delete monitoring data by org_id
            val orgId = transaction {
                projectIds.firstOrNull()?.let { projectId ->
                    Projects.selectAll()
                        .where { Projects.id eq projectId.toLong() }
                        .singleOrNull()?.get(Projects.organization_id)
                }
            }
            
            if (orgId != null) {
                val monitoringTables = listOf("system_metrics", "container_metrics")
                monitoringTables.forEach { table ->
                    try {
                        val query = "ALTER TABLE $table DELETE WHERE org_id = $orgId"
                        ClickHouseClient.execute(query)
                        logger.info { "Deleted ClickHouse monitoring data from $table for org $orgId" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to delete monitoring data from $table" }
                    }
                }
            }
            
            logger.info { "Completed ClickHouse data deletion for ${projectIds.size} projects" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete ClickHouse data" }
            throw e
        }
    }
}
