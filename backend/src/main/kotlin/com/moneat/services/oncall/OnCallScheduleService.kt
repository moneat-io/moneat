package com.moneat.services.oncall

import com.moneat.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime

class OnCallScheduleService {
    
    fun getSchedule(scheduleId: Int): OnCallSchedule? = transaction {
        val scheduleRow = OnCallSchedules
            .selectAll()
            .where { OnCallSchedules.id eq scheduleId }
            .singleOrNull() ?: return@transaction null
        
        val participants = OnCallParticipants
            .innerJoin(Users)
            .selectAll()
            .where { OnCallParticipants.scheduleId eq scheduleId }
            .orderBy(OnCallParticipants.position to SortOrder.ASC)
            .map { row ->
                OnCallParticipant(
                    id = row[OnCallParticipants.id].value,
                    userId = row[OnCallParticipants.userId],
                    userName = row[Users.name],
                    userEmail = row[Users.email],
                    position = row[OnCallParticipants.position]
                )
            }
        
        val overrides = OnCallOverrides
            .innerJoin(Users)
            .selectAll()
            .where { OnCallOverrides.scheduleId eq scheduleId }
            .orderBy(OnCallOverrides.startAt to SortOrder.ASC)
            .map { row ->
                OnCallOverride(
                    id = row[OnCallOverrides.id].value,
                    scheduleId = row[OnCallOverrides.scheduleId],
                    userId = row[OnCallOverrides.userId],
                    userName = row[Users.name],
                    startAt = row[OnCallOverrides.startAt].toString(),
                    endAt = row[OnCallOverrides.endAt].toString(),
                    createdBy = row[OnCallOverrides.createdBy],
                    createdAt = row[OnCallOverrides.createdAt].toString()
                )
            }
        
        val currentOnCall = getCurrentOnCall(scheduleId)
        
        OnCallSchedule(
            id = scheduleRow[OnCallSchedules.id].value,
            organizationId = scheduleRow[OnCallSchedules.organizationId],
            name = scheduleRow[OnCallSchedules.name],
            rotationType = scheduleRow[OnCallSchedules.rotationType],
            handoffTime = LocalTime.parse(scheduleRow[OnCallSchedules.handoffTime]),
            timezone = scheduleRow[OnCallSchedules.timezone],
            participants = participants,
            overrides = overrides,
            currentOnCall = currentOnCall,
            createdAt = scheduleRow[OnCallSchedules.createdAt].toString(),
            updatedAt = scheduleRow[OnCallSchedules.updatedAt].toString()
        )
    }
    
    fun getCurrentOnCall(scheduleId: Int): OnCallParticipant? = transaction {
        val now = Clock.System.now()
        
        // Check for active override first
        val override = OnCallOverrides
            .innerJoin(Users)
            .selectAll()
            .where { 
                (OnCallOverrides.scheduleId eq scheduleId) and
                (OnCallOverrides.startAt lessEq now) and
                (OnCallOverrides.endAt greater now)
            }
            .orderBy(OnCallOverrides.startAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
        
        if (override != null) {
            val participant = OnCallParticipants
                .selectAll()
                .where { 
                    (OnCallParticipants.scheduleId eq scheduleId) and
                    (OnCallParticipants.userId eq override[OnCallOverrides.userId])
                }
                .singleOrNull()
            
            return@transaction OnCallParticipant(
                id = participant?.get(OnCallParticipants.id)?.value ?: -1,
                userId = override[Users.id].value,
                userName = override[Users.name],
                userEmail = override[Users.email],
                position = participant?.get(OnCallParticipants.position) ?: -1
            )
        }
        
        // No override, calculate based on rotation
        val schedule = OnCallSchedules
            .selectAll()
            .where { OnCallSchedules.id eq scheduleId }
            .singleOrNull() ?: return@transaction null
        
        val participants = OnCallParticipants
            .innerJoin(Users)
            .selectAll()
            .where { OnCallParticipants.scheduleId eq scheduleId }
            .orderBy(OnCallParticipants.position to SortOrder.ASC)
            .toList()
        
        if (participants.isEmpty()) return@transaction null
        
        val rotationType = schedule[OnCallSchedules.rotationType]
        val tz = try { TimeZone.of(schedule[OnCallSchedules.timezone]) } catch (e: Exception) { TimeZone.UTC }
        
        val rotationDays = when (rotationType) {
            "DAILY" -> 1
            "WEEKLY" -> 7
            "CUSTOM" -> 7 // default to weekly for custom
            else -> 7
        }
        
        // Calculate days since epoch
        val epochSeconds = 0L
        val daysSinceEpoch = ((now.epochSeconds - epochSeconds) / 86400).toInt()
        
        // Calculate which participant should be on call
        val rotationCycle = (daysSinceEpoch / rotationDays) % participants.size
        val currentParticipant = participants[rotationCycle]
        
        OnCallParticipant(
            id = currentParticipant[OnCallParticipants.id].value,
            userId = currentParticipant[OnCallParticipants.userId],
            userName = currentParticipant[Users.name],
            userEmail = currentParticipant[Users.email],
            position = currentParticipant[OnCallParticipants.position]
        )
    }
    
    fun listSchedules(organizationId: Int): List<OnCallSchedule> = transaction {
        OnCallSchedules
            .selectAll()
            .where { OnCallSchedules.organizationId eq organizationId }
            .orderBy(OnCallSchedules.name to SortOrder.ASC)
            .mapNotNull { row ->
                getSchedule(row[OnCallSchedules.id].value)
            }
    }
    
    fun createSchedule(
        organizationId: Int,
        name: String,
        rotationType: String,
        handoffTime: LocalTime,
        timezone: String,
        participantIds: List<Int>
    ): OnCallSchedule = transaction {
        val now = Clock.System.now()
        
        val scheduleId = OnCallSchedules.insertAndGetId {
            it[OnCallSchedules.organizationId] = organizationId
            it[OnCallSchedules.name] = name
            it[OnCallSchedules.rotationType] = rotationType
            it[OnCallSchedules.handoffTime] = handoffTime.toString()
            it[OnCallSchedules.timezone] = timezone
            it[OnCallSchedules.createdAt] = now
            it[OnCallSchedules.updatedAt] = now
        }.value
        
        participantIds.forEachIndexed { index, userId ->
            OnCallParticipants.insert {
                it[OnCallParticipants.scheduleId] = scheduleId
                it[OnCallParticipants.userId] = userId
                it[position] = index
                it[createdAt] = now
            }
        }
        
        getSchedule(scheduleId)!!
    }
    
    fun updateSchedule(
        scheduleId: Int,
        name: String? = null,
        rotationType: String? = null,
        handoffTime: LocalTime? = null,
        timezone: String? = null,
        participantIds: List<Int>? = null
    ): OnCallSchedule? = transaction {
        val now = Clock.System.now()
        
        OnCallSchedules.update({ OnCallSchedules.id eq scheduleId }) {
            if (name != null) it[OnCallSchedules.name] = name
            if (rotationType != null) it[OnCallSchedules.rotationType] = rotationType
            if (handoffTime != null) it[OnCallSchedules.handoffTime] = handoffTime.toString()
            if (timezone != null) it[OnCallSchedules.timezone] = timezone
            it[updatedAt] = now
        }
        
        if (participantIds != null) {
            OnCallParticipants.deleteWhere { OnCallParticipants.scheduleId eq scheduleId }
            participantIds.forEachIndexed { index, userId ->
                OnCallParticipants.insert {
                    it[OnCallParticipants.scheduleId] = scheduleId
                    it[OnCallParticipants.userId] = userId
                    it[position] = index
                    it[createdAt] = now
                }
            }
        }
        
        getSchedule(scheduleId)
    }
    
    fun deleteSchedule(scheduleId: Int): Boolean = transaction {
        OnCallSchedules.deleteWhere { id eq scheduleId } > 0
    }
    
    fun createOverride(
        scheduleId: Int,
        userId: Int,
        startAt: Instant,
        endAt: Instant,
        createdBy: Int
    ): OnCallOverride = transaction {
        val now = Clock.System.now()
        
        val overrideId = OnCallOverrides.insertAndGetId {
            it[OnCallOverrides.scheduleId] = scheduleId
            it[OnCallOverrides.userId] = userId
            it[OnCallOverrides.startAt] = startAt
            it[OnCallOverrides.endAt] = endAt
            it[OnCallOverrides.createdBy] = createdBy
            it[createdAt] = now
        }.value
        
        val row = OnCallOverrides
            .innerJoin(Users)
            .selectAll()
            .where { OnCallOverrides.id eq overrideId }
            .single()
        
        OnCallOverride(
            id = row[OnCallOverrides.id].value,
            scheduleId = row[OnCallOverrides.scheduleId],
            userId = row[OnCallOverrides.userId],
            userName = row[Users.name],
            startAt = row[OnCallOverrides.startAt].toString(),
            endAt = row[OnCallOverrides.endAt].toString(),
            createdBy = row[OnCallOverrides.createdBy],
            createdAt = row[OnCallOverrides.createdAt].toString()
        )
    }
    
    fun deleteOverride(overrideId: Int): Boolean = transaction {
        OnCallOverrides.deleteWhere { id eq overrideId } > 0
    }
}
