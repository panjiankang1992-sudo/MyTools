#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository_root"

expected_business_jobs=(
  TaggingJob
  FileScanJob
  FileMaintenanceJob
  ThumbnailGenerationJob
  ManualVideoPackageJob
  MediaPackageAnalysisJob
  MediaDirectoryScanSidecarJob
  EbookMetadataIndexJob
  BookSourceChapterCacheJob
  ReaderCacheMaintenanceSidecarJob
)

expected_infrastructure_jobs=(
  DshEventHub
  MdnsAdvertisementService
  RegistrationMailSidecarPublisher
  AutomationOutboxRelay
  CompletionOutboxRelay
  AutomationRunReconciler
)

expected_sidecar_flags=(
  MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED
  MEDIA_TAG_SIDECAR_ENABLED
  MEDIA_PROCESSING_SIDECAR_ENABLED
  MEDIA_DIRECTORY_SCAN_SIDECAR_ENABLED
  READER_SEARCH_SIDECAR_ENABLED
  READER_IMPORT_SIDECAR_ENABLED
  READER_DISCOVERY_SIDECAR_ENABLED
  READER_CACHE_MAINTENANCE_SIDECAR_ENABLED
)

shared_client_services=(
  messaging-service
  drive-service
  media-library-service
  reader-service
  storage-gateway-service
)

for class_name in "${expected_business_jobs[@]}" "${expected_infrastructure_jobs[@]}"; do
  if ! rg -q "class ${class_name}" src/main/java service/*/src/main/java; then
    echo "Missing scheduled class from migration inventory: ${class_name}" >&2
    exit 1
  fi
done

for flag_name in "${expected_sidecar_flags[@]}"; do
  if ! rg -F -q '${'"${flag_name}"':false}' src/main/resources/application.yml \
      src/main/resources/application-prod.yml; then
    echo "Sidecar flag is missing or no longer defaults to false: ${flag_name}" >&2
    exit 1
  fi
done

for service_name in "${shared_client_services[@]}"; do
  if rg -q '/api/v1/task-instances|X-Task-Business-Token' "service/${service_name}/src/main/java" \
      --glob '*.java'; then
    echo "Direct Scheduler protocol usage is forbidden in ${service_name}" >&2
    exit 1
  fi
  if ! rg -q '<artifactId>task-scheduler-client</artifactId>' "service/${service_name}/pom.xml"; then
    echo "Shared Scheduler client dependency is missing in ${service_name}" >&2
    exit 1
  fi
done

business_count=$(rg -l '@Scheduled' \
  src/main/java/com/yuyutian/mytools/{localfile,media,reader,scheduler} --glob '*.java' | wc -l | tr -d ' ')
root_count=$(rg -l '@Scheduled' src/main/java --glob '*.java' | wc -l | tr -d ' ')
service_infrastructure_count=$(rg -l '@Scheduled' \
  service/{messaging-service,message-automation-service}/src/main/java --glob '*.java' | wc -l | tr -d ' ')

if [[ "$business_count" != "10" || "$root_count" != "13" || "$service_infrastructure_count" != "3" ]]; then
  echo "Scheduling inventory drift detected: business=${business_count}, root=${root_count}, service-infrastructure=${service_infrastructure_count}" >&2
  exit 1
fi

echo "Scheduling migration inventory verified: business=10, root=13, service-infrastructure=3, sidecar-flags=8, shared-client-services=5"
