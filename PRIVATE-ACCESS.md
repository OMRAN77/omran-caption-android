# Personal use rollout

This client supports the server protection in OMRAN77/omran-caption PR #12.
Use the Access token button to enter the same token configured as OMRAN_ACCESS_TOKEN
in the server's secret settings. Use 32–256 random printable ASCII characters
without spaces. Never send it in chat or commit it.

The token stays in process memory only. Re-enter it after process restart.
Changing it stops recording first. Unauthorized responses clear it and stop
capture rather than repeatedly calling the server. Missing server configuration
also stops capture with an explanatory message.

Install and verify this client before deploying server protection. Test correct
and incorrect tokens, start/stop, background overlays, and connectivity on a
real device. The PR workflow builds a test artifact only; it publishes no release.

The pre-existing debug signing key is committed in this public repository.
Its signature is therefore not proof that an APK came from the owner. Only
install builds from your own verified workflow. Replacing signing keys affects
upgrade compatibility and is intentionally not done by this change.
