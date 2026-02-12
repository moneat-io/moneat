# On-Call Production Seed Data

This seed script creates realistic production-style on-call data perfect for screenshots and demos.

## What It Creates

### Organization
- **Acme Corporation** (acme-corp) - A realistic tech company with 51-200 employees

### Users (All password: `e2e-test-password`)
- `oncall-admin@moneat.io` - Admin User (Owner)
- `sarah.chen@moneat.io` - Sarah Chen (Primary On-Call)
- `michael.rodriguez@moneat.io` - Michael Rodriguez
- `alex.kumar@moneat.io` - Alex Kumar (Database Team)
- `jessica.thompson@moneat.io` - Jessica Thompson (Secondary On-Call)
- `david.park@moneat.io` - David Park

### On-Call Schedules
1. **Primary On-Call** - Weekly rotation (Sarah → Michael → Alex)
2. **Secondary On-Call** - Weekly rotation (Jessica → David)
3. **Database Team** - Daily rotation (Alex → David)

### Escalation Policies
1. **Critical Production Alerts** (P0/P1) - Fast escalation, 3 repeats
   - Step 1: Primary On-Call (5 min timeout)
   - Step 2: Secondary On-Call (5 min timeout)
   - Step 3: Admin + Sarah (10 min timeout)

2. **High Priority Alerts** (P2) - Standard escalation, 2 repeats
   - Step 1: Primary On-Call (10 min timeout)
   - Step 2: Sarah (15 min timeout)

3. **Medium Priority Alerts** (P3) - Business hours only, 1 repeat
   - Step 1: Primary On-Call (30 min timeout)

### Incidents Created

#### 🔴 Active - TRIGGERED (Brand New)
1. **P0: Database Connection Pool Exhausted** (2 min ago)
   - Fresh incident, just triggered
   - Shows initial notification
   - Waiting for acknowledgment

2. **P2: Disk Space Warning** (12 min ago)
   - Triggered, not yet acknowledged
   - Lower priority, less urgent

3. **P3: SSL Certificate Expiring Soon** (4 hours ago)
   - Low priority alert
   - Business hours only

#### ⚠️ Active - ACKNOWLEDGED (Being Worked On)
4. **P1: API Response Time SLA Breach** (35 min ago, ack 28 min ago)
   - **Timeline shows:** Triggered → Timeout → Escalated → Acknowledged → Multiple notes
   - Demonstrates: Escalation flow, user collaboration
   - Sarah acknowledged and is working on it

5. **P1: Memory Leak Detected** (1h 45m ago, ack 1h 25m ago)
   - **Timeline shows:** Multiple escalations through 3 steps → Acknowledged → Ongoing investigation
   - Demonstrates: Full escalation chain, multiple team members notified, collaborative notes
   - Michael is working on it with notes from Alex

6. **P3: CDN Cache Hit Rate Low** (24 hours ago, acknowledged)
   - Old acknowledged incident
   - Shows long-running investigation

#### ✅ RESOLVED (Historical)
7. **P1: Production Deployment Failed** (resolved 3 hours ago) ⭐ **BEST FOR SCREENSHOTS**
   - **Timeline shows:** Full lifecycle with 13 events
   - Demonstrates: Timeout → Escalation → Ack → Notes → Reassignment → Resolution
   - Shows collaboration between Sarah and Michael
   - Has realistic deployment failure scenario with follow-up actions

8. **P2: Rate Limit Exceeded** (resolved 6 hours ago)
   - Quick resolution (15 minutes)
   - Shows efficient incident handling

9. **P2: Cache Miss Rate Spike** (resolved 8 hours ago)
   - Investigation and fix timeline
   - Shows debugging process

10. **P0: Kafka Consumer Lag Critical** (resolved 2 days ago)
    - Old incident for historical context
    - Shows overnight handoff between team members
    - Demonstrates reassignment workflow

## Usage

```bash
# Run the seed script
./scripts/seedOnCallProductionData.sh

# The script will:
# 1. Clean up any existing Acme Corp data
# 2. Create fresh data with timestamps relative to NOW()
# 3. Display a summary of what was created
```

## Best Views for Screenshots

### 1. Incident List
- Login as any user
- Navigate to On-Call → Incidents
- Shows mix of statuses, priorities, and timestamps
- Active incidents at top, resolved below

### 2. Active P0 Incident (Database Pool)
- Shows fresh triggered incident
- Basic timeline with trigger + notification
- Good for showing "just happened" state

### 3. Acknowledged P1 Incident (API Timeout)
- Timeline shows escalation flow
- Multiple notifications sent
- User actively investigating
- Notes showing progress

### 4. Acknowledged P1 Incident (Memory Leak) ⭐ **RICH TIMELINE**
- Shows full 3-step escalation chain
- Multiple people notified
- Collaboration between team members
- Ongoing investigation

### 5. Resolved Incident (Deployment Failed) ⭐ **COMPLETE LIFECYCLE**
- **Best for showing full features**
- 13 timeline events showing:
  - Initial trigger
  - Escalation due to timeout
  - Acknowledgment
  - Investigation notes
  - Root cause analysis
  - Reassignment between users
  - Follow-up actions
  - Resolution

### 6. Escalation Policies Page
- Shows 3 different policies
- Different escalation speeds
- Multiple steps per policy

### 7. On-Call Schedules Page
- 3 schedules with different rotations
- Weekly and daily rotations
- Different timezones

## Event Types Demonstrated

All timeline event types are represented:
- ✅ TRIGGERED - Incident created
- ✅ NOTIFICATION_SENT - Push/SMS/email sent
- ✅ STEP_TIMEOUT - Escalation step timed out
- ✅ ESCALATED - Moved to next escalation step
- ✅ ACKNOWLEDGED - User acknowledged incident
- ✅ NOTE_ADDED - User added investigation note
- ✅ REASSIGNED - Incident handed off to another user
- ✅ RESOLVED - Incident marked as resolved

## Realistic Scenarios

All incidents are based on real production incidents:
- Database connection pool exhaustion
- API latency/timeout issues
- Memory leaks
- Disk space warnings
- SSL certificate expiration
- Deployment failures
- Rate limiting
- Cache invalidation
- Kafka consumer lag
- CDN cache hit rate

Each incident has:
- Realistic descriptions
- Technical metadata (error rates, metrics, affected systems)
- Runbook references
- Investigation notes that tell a story
- Proper resolution notes

## Notes

- All timestamps are relative to NOW(), so data always looks fresh
- Incidents span from "2 minutes ago" to "2 days ago" for variety
- Timeline events preserve chronological order
- User collaboration is demonstrated through notes and reassignments
- Escalation policies match realistic PagerDuty/Opsgenie patterns
