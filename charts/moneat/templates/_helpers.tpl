{{/*
Expand the name of the chart.
*/}}
{{- define "moneat.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "moneat.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "moneat.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "moneat.selectorLabels" -}}
app.kubernetes.io/name: {{ include "moneat.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "moneat.labels" -}}
helm.sh/chart: {{ include "moneat.chart" .root }}
{{ include "moneat.selectorLabels" . }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- with .root.Values.global.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "moneat.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-env" (include "moneat.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "moneat.secretDataValue" -}}
{{- $root := .root -}}
{{- $key := .key -}}
{{- $value := default "" .value -}}
{{- $length := default 32 .length -}}
{{- $secretName := include "moneat.secretName" $root -}}
{{- $existing := lookup "v1" "Secret" $root.Release.Namespace $secretName -}}
{{- if $value -}}
{{- $value | b64enc -}}
{{- else if and $existing (hasKey $existing.data $key) -}}
{{- index $existing.data $key -}}
{{- else -}}
{{- randAlphaNum $length | b64enc -}}
{{- end -}}
{{- end -}}

{{- define "moneat.image" -}}
{{- printf "%s:%s" .image.repository (include "moneat.imageTag" .) -}}
{{- end -}}

{{- define "moneat.imageTag" -}}
{{- $root := .root -}}
{{- $image := .image -}}
{{- $globalTag := default $root.Chart.AppVersion $root.Values.image.tag -}}
{{- default $globalTag $image.tag -}}
{{- end -}}

{{- define "moneat.pullPolicy" -}}
{{- default .root.Values.image.pullPolicy .image.pullPolicy -}}
{{- end -}}

{{- define "moneat.postgresqlHost" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "%s-postgresql" (include "moneat.fullname" .) -}}
{{- else -}}
{{- required "externalPostgresql.host is required when postgresql.enabled=false and externalPostgresql.url is empty" .Values.externalPostgresql.host -}}
{{- end -}}
{{- end -}}

{{- define "moneat.databaseUrl" -}}
{{- if .Values.externalPostgresql.url -}}
{{- .Values.externalPostgresql.url -}}
{{- else -}}
{{- $database := ternary .Values.postgresql.auth.database .Values.externalPostgresql.database .Values.postgresql.enabled -}}
{{- $port := ternary .Values.postgresql.service.port .Values.externalPostgresql.port .Values.postgresql.enabled -}}
{{- printf "jdbc:postgresql://%s:%v/%s" (include "moneat.postgresqlHost" .) $port $database -}}
{{- end -}}
{{- end -}}

{{- define "moneat.databaseUser" -}}
{{- if .Values.postgresql.enabled -}}
{{- .Values.postgresql.auth.username -}}
{{- else -}}
{{- .Values.externalPostgresql.username -}}
{{- end -}}
{{- end -}}

{{- define "moneat.clickhouseUrl" -}}
{{- if .Values.clickhouse.enabled -}}
{{- printf "http://%s-clickhouse:%v" (include "moneat.fullname" .) .Values.clickhouse.service.httpPort -}}
{{- else -}}
{{- required "externalClickHouse.url is required when clickhouse.enabled=false" .Values.externalClickHouse.url -}}
{{- end -}}
{{- end -}}

{{- define "moneat.clickhouseDatabase" -}}
{{- if .Values.clickhouse.enabled -}}
{{- .Values.clickhouse.auth.database -}}
{{- else -}}
{{- .Values.externalClickHouse.database -}}
{{- end -}}
{{- end -}}

{{- define "moneat.clickhouseUser" -}}
{{- if .Values.clickhouse.enabled -}}
{{- .Values.clickhouse.auth.username -}}
{{- else -}}
{{- .Values.externalClickHouse.username -}}
{{- end -}}
{{- end -}}

{{- define "moneat.redisHost" -}}
{{- if .Values.redis.enabled -}}
{{- printf "%s-redis" (include "moneat.fullname" .) -}}
{{- else -}}
{{- required "externalRedis.host is required when redis.enabled=false and externalRedis.url is empty" .Values.externalRedis.host -}}
{{- end -}}
{{- end -}}

{{- define "moneat.redisUrl" -}}
{{- if .Values.externalRedis.url -}}
{{- .Values.externalRedis.url -}}
{{- else -}}
{{- printf "redis://:$(REDIS_PASSWORD)@%s:%v" (include "moneat.redisHost" .) .Values.externalRedis.port -}}
{{- end -}}
{{- end -}}

{{- define "moneat.backendPvcName" -}}
{{- printf "%s-backend-storage" (include "moneat.fullname" .) -}}
{{- end -}}

{{- define "moneat.backendSplitPvcName" -}}
{{- printf "%s-backend-%s" (include "moneat.fullname" .root) .name -}}
{{- end -}}

{{- define "moneat.datadogServiceAccountName" -}}
{{- if .Values.datadog.serviceAccount.create -}}
{{- printf "%s-datadog-agent" (include "moneat.fullname" .) -}}
{{- else -}}
{{- default (printf "%s-datadog-agent" (include "moneat.fullname" .)) .Values.datadog.serviceAccount.name -}}
{{- end -}}
{{- end -}}
