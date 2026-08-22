# MCP Server Examples

## Investigating an Error Spike

```text
User: "There's been a spike in errors for project 42. What's happening?"

Agent calls: list_issues(project_id=42, status="unresolved", limit=10)
Agent calls: query_logs(query="error", levels=["error"], limit=20)
Agent calls: list_hosts()

Agent: "I found 3 new unresolved issues in project 42:
1. NullPointerException in UserService.getProfile (153 events)
2. TimeoutException in PaymentGateway.charge (47 events)  
3. ConnectionRefusedException in DatabasePool.acquire (12 events)

The database connection errors correlate with host db-primary-01 showing
elevated CPU usage. This suggests a database performance issue is cascading
into application errors."
```

## Creating a Dashboard

```text
User: "Create a dashboard to monitor our API performance"

Agent calls: create_dashboard(name="API Performance Overview")
Agent: "Created dashboard 'API Performance Overview' (ID: 15).
You can now add widgets to it via the Moneat dashboard UI."
```

## Triaging On-Call Alerts

```text
User: "Which on-call alerts are firing right now?"

Agent calls: list_on_call_alerts(status="triggered")
Agent calls: list_on_call_alerts(status="acknowledged")

Agent: "There are 2 active on-call alerts:
1. [P1] Database connection pool exhausted (triggered 15m ago, unacknowledged)
2. [P3] Elevated 5xx rate on api-gateway (acknowledged by @alice 5m ago)

The P1 alert needs immediate attention."

User: "Tell me more about the P1 alert"

Agent calls: get_on_call_alert(alert_id="a3f1c8d2-9e4b-4c7a-bf10-2d6e5a9c1b34")
Agent calls: query_logs(query="connection pool", levels=["error"], limit=10)
Agent calls: get_host_status(host_id=123)

Agent: "The P1 alert was triggered by the 'Database CPU > 90%' alert on
host db-primary-01. The host is currently at 94% CPU utilization. 
Recent logs show repeated 'connection pool exhausted' errors from 3 services.
This appears to be caused by a long-running query blocking the connection pool."
```

## Checking Uptime Status

```text
User: "Are all our uptime monitors healthy?"

Agent calls: list_uptime_monitors()

Agent: "You have 5 uptime monitors:
- api.example.com: UP (99.98% uptime, avg 145ms)
- web.example.com: UP (99.95% uptime, avg 230ms)
- auth.example.com: UP (100% uptime, avg 89ms)
- payments.example.com: DOWN (last check failed 2m ago)
- docs.example.com: UP (99.99% uptime, avg 112ms)

Note: payments.example.com is currently DOWN."

User: "What happened with payments?"

Agent calls: get_monitor_heartbeats(monitor_id="payments-uuid", hours=1)

Agent: "The payments monitor started failing 8 minutes ago. The last 
successful check was at 14:52 UTC with a 156ms response time. Since then,
all checks are returning connection timeouts. This correlates with the 
database incident - the payments service likely depends on the affected database."
```

## Global Search

```text
User: "Search for anything related to 'timeout' across our infrastructure"

Agent calls: global_search(query="timeout", limit=5)

Agent: "Found matches across multiple sources:
- Logs: 47 timeout-related log entries in the last hour
- Hosts: No hosts with 'timeout' in the name

The timeouts are concentrated in the payment-service and user-service,
both connecting to db-primary-01."
```
