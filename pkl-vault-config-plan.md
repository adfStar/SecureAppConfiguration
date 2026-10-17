# Plan: App Configuration + HashiCorp Vault Secrets via Pkl (KMP)

A portable, company-wide blueprint for managing typed application configuration
with Pkl, sourcing secret values from HashiCorp Vault, implemented as a Kotlin
Multiplatform (KMP) library usable from iOS (Swift) and Android (Kotlin)
projects, plus CI/CD.

---

## 1. Objective

- Keep **non-secret config in Pkl**: typed, validated, versioned in git.
- Source **secret values from Vault** at evaluation (build) time.
- Provide **one Kotlin codebase** (no Go, no extra language) that plugs into
  every surface: iOS build, Android build, and CI/CD.
- Deliver it as a reusable **blueprint/starter** other company projects adopt.

### Key decisions (locked)
| Topic | Decision |
|-------|----------|
| Integration style | Custom Pkl `vault:` resource reader |
| Implementation language | Kotlin Multiplatform (shared core + thin adapters) |
| Secret resolution timing | **Eval-time** (baked into generated config at build) |
| Vault auth | **Token** (`VAULT_ADDR` + `VAULT_TOKEN`), isolated for later swap |
| Target platforms | iOS (Swift) + Android (Kotlin), mixed CI |
| Bundle protection | **Promon** shields/encrypts bundled files post-build, decrypts at runtime |

---

## 2. Why eval-time baking is acceptable here

Eval-time resolution writes resolved secrets into generated Swift/Kotlin that
ships inside the app bundle. Normally that is unsafe for mobile (bundles are
extractable). **In this environment Promon app shielding encrypts the bundled
files after the binary is built and decrypts them at runtime**, so baked config
is protected at rest. This removes the usual "no secrets in the artifact"
blocker for our policy.

Residual guidance (not blockers):
- Secrets are still decrypted in-memory at runtime; prefer a backend/BFF for the
  very highest-value secrets where one already exists.
- Keep a `target = app | server` flag and an optional `clientSafe` schema marker
  so the reader can enforce per-project rules if a team opts in.

---

## 3. Architecture

Split into **portable logic** (shared) and **binding adapters** (thin, per
platform). All Vault work is pure Kotlin via the multiplatform Ktor client.

```
pkl-vault-kmp/
├─ commonMain/                 # portable logic
│   ├─ VaultUri.kt             # parse "vault:secret/data/app#key"
│   ├─ VaultClient.kt          # Ktor: GET KV v2, X-Vault-Token header
│   ├─ VaultResolver.kt        # uri -> ByteArray; cache; fail-closed; target guard
│   └─ Redaction.kt            # never log secret values
├─ jvmMain/                    # Android + CI (JVM) builds
│   └─ PklVaultReader.kt       # implements org.pkl.core.resource.ResourceReader
├─ nativeMain/                 # macosArm64/x64, linuxX64, ios*
│   └─ ExternalReaderMain.kt   # pkl external-reader stdio message-protocol entry
├─ swift-adapter/              # tiny Swift shim conforming to pkl-swift ResourceReader
│   └─ VaultResourceReader.swift
└─ build.gradle.kts            # KMP targets + Ktor engines (JVM, Darwin, Native)
```

All surfaces drive identical Pkl:

```pkl
password = read("vault:secret/data/myapp/db#password").text
```

### Consumption modes (one codebase → four modes)
| Surface | Mechanism | Artifact used |
|---------|-----------|---------------|
| Android build | Register reader on pkl Evaluator / pkl-gradle | JVM jar |
| CI / BFF / server | Embedded JVM **or** external reader | JVM jar / native exe |
| iOS build | Swift adapter → shared Kotlin/Native framework | iOS framework |
| Any pkl CLI | `--external-resource-reader vault=./pkl-vault` | native executable |

---

## 4. Components (what to build)

### 4.1 `commonMain`
- **VaultUri**: parse `vault:<mount>/data/<path>#<key>`; KV v2 aware; optional
  fragment selects a field, else whole JSON. Validate, reject malformed.
- **VaultClient** (Ktor): `GET {VAULT_ADDR}/v1/<mount>/data/<path>`, header
  `X-Vault-Token: <token>`, optional `X-Vault-Namespace`. Parse KV v2
  `data.data`. Typed errors (auth failure, 404, network).
- **VaultResolver**: `resolve(uri): ByteArray`. Per-run cache + de-dupe,
  fail-closed when token/addr missing, applies `target` guard, routes through
  `Redaction`.
- **Redaction**: wrappers ensuring secret values are never logged/printed.

### 4.2 `jvmMain`
- **PklVaultReader**: implements `org.pkl.core.resource.ResourceReader`
  (`uriScheme = "vault"`, `read(uri)`, non-hierarchical, non-globbable),
  delegating to `VaultResolver`. Registered via pkl-config-kotlin Evaluator and
  the pkl-gradle task.

### 4.3 `nativeMain`
- **ExternalReaderMain**: standalone executable speaking Pkl's external
  resource-reader message protocol over stdio; delegates to `VaultResolver`.
  Build targets: `macosArm64`, `macosX64`, `linuxX64` (extend as needed).

### 4.4 Swift adapter
- **VaultResourceReader.swift**: conforms to pkl-swift's `ResourceReader`
  protocol; forwards `read(url:)` to the shared Kotlin/Native `VaultResolver`
  exposed through the iOS framework. Wired into the SPM/Xcode build step.

### 4.5 Shared Pkl package `company.config`
- `AppConfig.pkl` (typed schema), `secrets.pkl` (helpers like `vaultField`),
  optional `clientSafe` marker + guard. Published to the internal Pkl package
  registry for reuse.

```pkl
// secrets.pkl (library helper)
function vaultField(mount: String, path: String, key: String): String =
  read("vault:\(mount)/data/\(path)#\(key)").text
```

---

## 5. Integration recipes

### iOS (Swift, build-time codegen)
1. Add pkl-swift + SPM build plugin; generate Swift from Pkl.
2. Register `VaultResourceReader` (Swift adapter) on the evaluator used by codegen.
3. Build env provides `VAULT_ADDR` + `VAULT_TOKEN` (keychain / CI).
4. Generated config compiled into the app; **Promon** encrypts the bundle post-build.

### Android (Kotlin, Gradle)
1. Apply pkl-gradle; use `pkl-gen-kotlin`.
2. Add `pkl-vault-kmp` (JVM) and register `PklVaultReader` on the pkl task.
3. Build env provides `VAULT_ADDR` + `VAULT_TOKEN`.
4. Generated config compiled in; **Promon** encrypts the bundle post-build.

### CI / server (pkl CLI)
1. Ship the native external-reader executable (or JVM jar).
2. `pkl eval --external-resource-reader vault=./pkl-vault config.pkl`.
3. CI injects a short-TTL `VAULT_TOKEN`; render to an **ephemeral** artifact.

---

## 6. Security model

- **Auth** isolated in `VaultClient`; Token today, swappable to AppRole/K8s with
  no Pkl changes.
- **Short-TTL tokens**; fail-closed when `VAULT_TOKEN`/`VAULT_ADDR` absent.
- **Redaction**: reader never logs values; CI must not echo rendered output.
- **At rest in bundle**: protected by Promon encryption; decrypted at runtime.
- **Non-bundle outputs** (CI-rendered server config) treated as sensitive:
  ephemeral files, `.gitignore`, no log echo.
- Optional per-project `target` guard / `clientSafe` marker for teams that want
  to keep specific high-value secrets out of client builds.

---

## 7. Work breakdown (milestones)

**M0 — Scaffolding**
- [ ] Create `pkl-vault-kmp` KMP Gradle module (common/jvm/native targets, Ktor engines).
- [ ] CI to build JVM jar + native executables.

**M1 — Shared core (`commonMain`)**
- [ ] `VaultUri` parser + tests.
- [ ] `VaultClient` (KV v2, token, namespace) + tests (mock engine).
- [ ] `VaultResolver` (cache, fail-closed, target guard) + tests.
- [ ] `Redaction`.

**M2 — Bindings**
- [ ] JVM `PklVaultReader` + integration test with pkl-config-kotlin.
- [ ] Native `ExternalReaderMain` + test against `pkl eval`.
- [ ] Swift adapter conforming to pkl-swift `ResourceReader`.

**M3 — Pkl package**
- [ ] `company.config` (`AppConfig`, `secrets.pkl`, guard); publish to registry.

**M4 — Pilots**
- [ ] iOS app (this pushTAN project) via SPM build plugin.
- [ ] One Android app via pkl-gradle.
- [ ] One CI service via external reader.

**M5 — Hardening & docs**
- [ ] Promon pipeline integration verified (encrypt post-build, decrypt runtime).
- [ ] Three integration recipes documented; starter repo templated.
- [ ] Auth-swap guide (Token → AppRole/K8s).

---

## 8. Open questions / to confirm

- Vault KV version(s) and mount layout (KV v2 assumed).
- Vault namespaces in use (Enterprise)?
- Token sourcing per environment (local keychain vs CI secret store).
- Minimum pkl / pkl-swift / pkl-gradle versions to pin.
- Which native targets CI must produce (macOS arm64/x64, linux).
- Promon pipeline hook points and whether any pre-encryption validation is needed.

---

## 9. References

- Pkl language & resources: https://pkl-lang.org/main/current/index.html
- Pkl Swift bindings: https://pkl-lang.org/swift/current/
- Pkl Gradle / codegen: https://pkl-lang.org/main/current/
- HashiCorp Vault KV v2 API: https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2
- Ktor multiplatform client: https://ktor.io/docs/client-create-multiplatform-application.html
