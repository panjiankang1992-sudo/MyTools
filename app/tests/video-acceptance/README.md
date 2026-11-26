# Video App acceptance fixture

This is a localhost UI fixture, not an inference backend. Never deploy it or enable production modes based on its results.

1. Run `python3 app/tests/video-acceptance/prepare_build.py /tmp/mytools-video-acceptance-app-new` from the repository. The destination must not exist. This copies the app, injects a separate test entry, and leaves production authentication and source unchanged.
2. Build the temporary app with the project's `assembleHap` command from that temporary app directory. Install it only on a designated simulator; installing with the production bundle ID replaces the simulator app until restoration.
3. Start `server.py --image <test.png> --video <test.mp4> --events <events.jsonl>`. It listens only on 127.0.0.1:23110. Create an HDC reverse mapping `rport tcp:23110 tcp:23110` for the simulator.
4. The test header selects synthetic accounts A/B or destroys/recreates the production video component. Media preparation buttons use system save dialogs, allowing the actual production pickers to select test files.
5. GET `/_control` returns fixture state. POST accepts `offline`, `loseAck`, or `jobId` plus `status`. `loseAck` commits one job and returns 502; retry must reuse its idempotency key. Status changes are simulated. The returned movie is always the supplied static file.
6. Record actual UI states and requests. The fixture does not validate codecs, sizes, masks, model quality, persistence, or production authorization. Do not treat its permissive mode catalog as release approval.
7. Export state without its ticket table. Rebuild/install/start the normal app, verify its real model gates, remove the exact reverse mapping, and stop only the fixture server process. Do not clear the user's app data.

Evidence for the 2026-09-21 run is in `docs/verification/2026-09-21-video-app-isolated/README.md`.
