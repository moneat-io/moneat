#!/bin/sh
# Entrypoint script for the Moneat backend.
# When the Datadog Java agent JAR is present and DD_AGENT_HOST is set,
# attaches the agent for profiling, APM, and dynamic instrumentation.

JAVA_OPTS=""

if [ -f /app/dd-java-agent.jar ] && [ -n "${DD_AGENT_HOST}${DD_TRACE_AGENT_URL}" ]; then
  JAVA_OPTS="-javaagent:/app/dd-java-agent.jar -Ddd.profiling.enabled=true"
fi

exec java $JAVA_OPTS -jar /app/app.jar "$@"
