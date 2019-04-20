{{- define "auth.fullname" -}}
  {{- if .Values.fullnameOverride -}}
    {{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
  {{- else -}}
    {{- printf "%s-%s" .Release.Name .Chart.Name -}}
  {{- end -}}
{{- end -}}

{{/* Auth Labels Template */}}
{{- define "auth.labels" }}
{{- range $key, $value := .Values.labels }}
{{ $key }}: {{ $value | quote }}
{{- end }}
heritage: {{ .Release.Service | quote }}
release: {{ .Release.Name | quote }}
chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/* Auth Environment Variables */}}
{{- define "auth.environmentvariables" }}
- name: SERVICE_PORT
  value: {{ .Values.service.internalPort | quote }}
{{- end }}

{{/* Customer Init Container Template */}}
{{- define "auth.customer.initcontainer" }}
{{- if not (or .Values.global.istio.enabled .Values.istio.enabled) }}
- name: test-customer
  image: {{ .Values.bash.image.repository }}:{{ .Values.bash.image.tag }}
  imagePullPolicy: {{ .Values.bash.image.pullPolicy }}
  command:
  - "/bin/bash"
  - "-c"
  - "until curl --max-time 1 {{ include "auth.customer.url" . }}; do echo waiting for customer-service; sleep 1; done"
  resources:
{{ toYaml .Values.resources | indent 4 }}
  securityContext:
  {{- include "auth.securityContext" . | indent 4 }}
{{- end }}
{{- end }}

{{/* Auth Customer URL Environment Variables */}}
{{- define "auth.customer.environmentvariables" }}
- name: CUSTOMER_URL
  value: {{ template "auth.customer.url" . }}
{{- end }}

{{- define "auth.customer.url" -}}
  {{- if .Values.customer.url -}}
    {{ .Values.customer.url }}
  {{- else -}}
    {{/* assume one is installed with release */}}
    {{- printf "http://%s-customer:8082" .Release.Name -}}
  {{- end }}
{{- end -}}

{{/* Auth HS256KEY Environment Variables */}}
{{- define "auth.hs256key.environmentvariables" }}
- name: HS256_KEY
  valueFrom:
    secretKeyRef:
      name: {{ template "auth.hs256key.secretName" . }}
      key:  key
{{- end }}

{{/* Auth HS256KEY Secret Name */}}
{{- define "auth.hs256key.secretName" -}}
  {{- if .Values.global.hs256key.secretName -}}
    {{ .Values.global.hs256key.secretName -}}
  {{- else if .Values.hs256key.secretName -}}
    {{ .Values.hs256key.secretName -}}
  {{- else -}}
    {{- .Release.Name }}-{{ .Chart.Name }}-hs256key
  {{- end }}
{{- end -}}

{{/* Auth Security Context */}}
{{- define "auth.securityContext" }}
{{- range $key, $value := .Values.securityContext }}
{{ $key }}: {{ $value }}
{{- end }}
{{- end }}

{{/* Istio Gateway */}}
{{- define "auth.istio.gateway" }}
  {{- if or .Values.global.istio.gateway.name .Values.istio.gateway.enabled .Values.istio.gateway.name }}
  gateways:
  {{ if .Values.global.istio.gateway.name -}}
  - {{ .Values.global.istio.gateway.name }}
  {{- else if .Values.istio.gateway.enabled }}
  - {{ template "auth.fullname" . }}-gateway
  {{ else if .Values.istio.gateway.name -}}
  - {{ .Values.istio.gateway.name }}
  {{ end }}
  {{- end }}
{{- end }}