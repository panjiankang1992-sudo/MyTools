# Isolated native subpage UI acceptance

This harness is not a login bypass and is never added to the production page list. It copies the app to a new private temporary directory and only replaces that copy's page entry. `AppControls`, the download page, RSS page, PDF page, fonts and assets are copied from the current production source.

```bash
python3 app/tests/subpage-ui-acceptance/prepare_build.py /private/tmp/mytools-ui-subpage-probe-UNIQUE
python3 -m unittest discover -s app/tests/subpage-ui-acceptance -p 'test_*.py'
python3 app/tests/subpage-ui-acceptance/fixture_server.py
```

Build the temporary project with the same DevEco/JBR/node environment as the app. Do not run old scripts that replace the production entry in place. Keep the generated HAP separate from the final production HAP.

The fixture only binds `127.0.0.1:23460`. The isolated auth subclass accepts exactly that origin and a fixed fixture identity. It never restores an account. GET requests provide empty history/RSS data. Every write returns HTTP 405 without side effects. Configure a device-to-host HDC loopback reverse port for the dedicated test emulator; do not point the probe at production.

The top acceptance menu exposes:

- `Actions`: real `AppEqualActionRow` with three direct `AppGroupedButton` roots and a deliberately long localized middle label. Record the measured child count; a source list of three items alone does not prove that the runtime mounted three nodes.
- `Download`: the unchanged real `DownloadToolsPage`, empty fixture history and normal composer.
- `PDF`: the unchanged real local PDF page. UI navigation is safe; do not execute file operations when auditing layout.
- `RSS`: the unchanged real RSS component initialized to its public `manage` state, backed by empty read-only fixtures.
- `Width 280`, `Width 320`, `Full`: bounded acceptance panel widths. These are layout controls, not system font-scale controls.

The acceptance ability retains the production system font-scale publication, and registers the same bundled fonts. A local diagnostic build may add `UI_ROW` dimension-only console logs to the temporary copy of `AppControls` to inspect actual measured child count and constraints. Such instrumentation must be recorded in the artifact manifest and must not be copied into production.

The `Actions` panel exposes `Loading`, `Available`, `Label`, and `Visibility` toggles above the real grouped buttons. Click the first and last grouped buttons to verify their separate counters. Change the label and confirm all three nodes remain present and remeasure to a common height. While loading or unavailable, clicking the first button must leave its counter unchanged, while the last button remains usable. Restore both flags and confirm the first callback works again. Toggle `Visibility` off and on: the third button is conditionally mounted with `if`, so the measured child count must change from three to two and back to three; the restored last button must still increment its original counter. The state text and counters make these outcomes inspectable without any network write.

After verification, restore the final production HAP to the test emulator if it will be used normally. Never count isolated component rendering as successful production-account login or a production business workflow test.
