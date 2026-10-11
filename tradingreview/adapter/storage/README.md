# Trading Import raw storage (local adapter)

The local adapter stores only AES-GCM ciphertext under a configurable directory outside the repository. Each artifact has a random DEK; the DEK is wrapped by a PKCS12 AES secret key and only the wrapped value is persisted in PostgreSQL. The selected alias is used for new uploads; keep older aliases in the keystore while their artifacts remain available for reprocessing. Use a distinct keystore and storage directory for every environment. Do not place either inside the worktree.

Configure these Spring properties through the deployment secret/config provider:

| Property | Purpose |
| --- | --- |
| `refinvest.trading-review.raw.root` | Writable encrypted object root |
| `refinvest.trading-review.raw.keystore-path` | PKCS12 keystore path |
| `refinvest.trading-review.raw.keystore-password` | Keystore/key password |
| `refinvest.trading-review.raw.key-alias` | AES secret key alias and stored key version |
| `refinvest.trading-review.version-set-json` | Current exact Normalizer/Reconstruction VersionSet from the implementation registry |

The adapter is unavailable and upload returns `503` when storage configuration is absent. Session creation returns `VERSION_UNAVAILABLE` until an exact VersionSet is configured. The version pin must come from an immutable Compute implementation registry; this repository does not invent implementation digests. A production KMS/object-storage adapter can implement `RawArtifactStorage` without changing the application service.

Spring's servlet multipart buffering is disabled. The upload controller parses two parts with bounded headers and streams the file through SHA-256 and authenticated encryption. Operational request body limits and timeouts at the ingress should match the 52,428,800 byte artifact limit plus multipart overhead. The application enforces the artifact byte limit while reading.

The API requires filename metadata but Core validates it only as a transport hint. New artifact rows store `NULL` for filename; object keys and public responses do not contain the original filename.

The retention and deletion workers run by default. `refinvest.trading-review.retention-enabled` and `refinvest.trading-review.deletion-enabled` control them. Orphan cleanup runs when the local adapter is configured. Its operational `refinvest.trading-review.raw.orphan-grace-seconds` setting defaults to one hour and has a ten-minute minimum; it removes at most 20 objects per run. Do not turn off deletion workers in a deployment that accepts uploads.
