UPDATE task_definition
SET result_schema='{"type":"object","required":["libraryRevision","directoryCount","completedScanCount","stagingScanCount","itemCount","sourceRelationCount","sourceTagRelationCount","readyCount","missingCount","analyzingCount","succeededAnalysisCount","failedAnalysisCount","runningAnalysisCount","tagRelationCount","artifactCount","readyDirectoryEntryCount","missingDirectoryEntryCount","digestSha256"]}',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name='media_reconcile_library';
