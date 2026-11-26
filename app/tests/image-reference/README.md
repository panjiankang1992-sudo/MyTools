# Native reference preparation acceptance

`ImageReferenceAcceptance.ets` is a temporary-build-only entry. It imports the production `ImageReferencePreparation` code. Copy it into `entry/src/main/ets/pages` in a disposable app copy, select it as the temporary main page, and provide these raw resources:

- over-one.png: 800x800 RGB noise PNG (~1.9 MB)
- over-five.png: 2048x2048 RGB noise PNG (~12.6 MB)
- high-resolution.jpg: 8000x6000 JPEG (48 MP)

Use a dedicated simulator and a loopback-only POST collector on port 23111 with an HDC reverse mapping. The test reports before/after sizes and sends actual ImageKit output to the collector; check output format, dimensions and hashes. Never install this entry on a user's phone. Restore the formal HAP and remove only the owned forwarding/collector afterward.

Pure policy tests: `bash app/scripts/test-image-reference-policy.sh` and `bash app/scripts/test-authorized-api-policy.sh` from the repository root.

Recorded native and real backend results: `docs/verification/2026-09-27-image-reference/README.md`.
