#!/bin/sh
# Entrypoint script for the Moneat backend.
# When the Datadog Java agent JAR is present and DD_AGENT_HOST is set,
# attaches the agent for profiling, APM, and dynamic instrumentation.

if [ -f /app/dd-java-agent.jar ] && [ -n "${DD_AGENT_HOST}${DD_TRACE_AGENT_URL}" ]; then
  exec java -javaagent:/app/dd-java-agent.jar -Ddd.profiling.enabled=true -jar /app/app.jar "$@"
fi

exec java -jar /app/app.jar "$@"
