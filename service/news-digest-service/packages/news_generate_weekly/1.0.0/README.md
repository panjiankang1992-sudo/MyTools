# Manual weekly news worker

`jobId` is the opaque **dispatch ID**. The worker obtains the stable report/job ID, frozen window and attempt from the news service under the current Scheduler execution lease. No owner, command, URL or cutoff is accepted from task parameters.

Required package environment: `NEWS_SERVICE_URL`, `NEWS_JOB_TOKEN`, `NEWS_WEEKLY_RUNTIME`, `NEWS_WEEKLY_CONFIG`, `NEWS_WEEKLY_WORK_ROOT`, and `HOME` set to the dedicated execution user's home. The runtime path must reference a verified immutable release. That user must already own the DSH workspace and private grouping socket. Do not broaden socket permissions or run this package under the shared executor identity.

The stable job directory retains collection and analysis checkpoints across execution retries; uploading images and publishing the report are idempotent. Each API call reads the current lease file. API errors abort the worker without exposing credentials. The Executor owns process-tree cancellation and the total time budget.

Images are resized from collected original bytes to JPEG/PNG for private authenticated access. There is no generated-image, email, RSS publication, cron or database write path in this package. Publish only after package validation; never edit this version once published.
