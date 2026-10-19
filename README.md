# pkl-vault — typed app config with Vault secrets, one codebase for every platform

> **The idea in one sentence:** write your application configuration once in
> [Pkl](https://pkl-lang.org) — typed, validated, versioned in git — and let
> secret values come straight from **HashiCorp Vault** at build time, using a
> single **Kotlin Multiplatform** library that plugs into iOS, Android and CI/CD.

---

## Why this exists

Every project re-invents config and secrets handling: `.env` files, plist
hacks, hard-coded keys, a different mechanism per platform. That is error-prone,
untyped, and leaks secrets into git and bundles.

`pkl-vault` replaces all of that with **one** approach:

| Problem today | With pkl-vault |
|---|---|
| Config is untyped strings scattered across platforms | One **typed, validated** Pkl schema, shared company-wide |
| Secrets copy-pasted into `.env` / plists / source | Secrets read from **Vault**, never stored in git |
| A different integration per platform/language | **One Kotlin codebase**; thin adapters for iOS, Android, CLI |
| "Is this value a secret?" is tribal knowledge | Secrets are explicit in the schema and guarded |

**Locked decisions** (the foundation of the design):

| Topic | Decision |
|---|---|
| Integration style | Custom Pkl `vault:` resource reader |
| Implementation language | Kotlin Multiplatform (shared core + thin adapters) |
| Secret resolution timing | **Eval-time** — baked into generated config at build |
| Vault auth | **Token** (`VAULT_ADDR` + `VAULT_TOKEN`), isolated for later swap |
| Platforms | iOS (Swift) + Android (Kotlin) + CI |
| Bundle protection | **Promon** shields/encrypts bundled files post-build |

---

## How it works (the 60-second version)

You write ordinary Pkl, and read a secret with a `vault:` URI:

```pkl
// app.pkl
amends "company.config.AppConfig"

apiBaseUrl = "https://api.example.com"
database {
  host = "db.internal"
  // Fetched from Vault at build time, pinned to secret version 3:
  password = secrets.vaultField("secret", "myapp/db", 3, "password")
}
```

When Pkl evaluates this file, our **`vault:` resource reader** intercepts the
read, fetches the secret from Vault over HTTPS, and returns the value. Pkl then
generates typed Swift/Kotlin that your app compiles in.

```
┌────────────┐   read("vault:…")   ┌───────────────┐   HTTPS GET /v1/…   ┌─────────┐
│  your .pkl │ ──────────────────► │ pkl-vault      │ ──────────────────► │  Vault  │
│  config    │                     │ resource reader│   X-Vault-Token     │  KV v2  │
└────────────┘ ◄────────────────── └───────────────┘ ◄────────────────── └─────────┘
      │          resolved value
      ▼
 generated Swift / Kotlin  ──►  compiled into the app  ──►  Promon-encrypted bundle
```

**One library, four modes** — the same Kotlin core drives all of them:

| Surface | Mechanism | Artifact |
|---|---|---|
| Android build | Reader registered on the Pkl Gradle task | JVM jar |
| iOS build | Swift adapter → shared Kotlin/Native framework | iOS framework |
| CI / server | Embedded JVM reader (native external reader deferred) | JVM jar |
| Any `pkl` CLI | `--external-resource-reader vault=./pkl-vault` | native executable *(deferred)* |

---

## The `vault:` URI format

```
vault:<mount>/data/<path>?version=<n>#<field>
      └─ KV v2 mount        │          └─ optional field selector
                            └─ REQUIRED KV v2 version
```

- `vault:secret/data/myapp/db?version=3#password` → the `password` field of
  **version 3** of that secret.
- `vault:secret/data/myapp/db?version=3` → the **whole** secret object (version 3)
  as JSON.

Two parts are required:

- The literal `data` segment — it mirrors the real Vault KV v2 API path, so the
  URI is a transparent view of the request being made.
- The `?version=<n>` query — **versioning is mandatory** (see
  [Reproducible builds](#reproducible-builds--secret-versioning)). An unversioned
  `vault:` URI is rejected at evaluation time, so a build can never silently pick
  up a newer secret than it shipped with.

Use the typed helper rather than hand-writing URIs — it makes the version
**impossible to forget**, because Pkl requires the `version` argument:

```pkl
// secrets.pkl (library helper) — `version` is a required parameter
function vaultField(mount: String, path: String, version: Int, field: String): String =
  read("vault:\(mount)/data/\(path)?version=\(version)#\(field)").text

// usage
password = vaultField("secret", "myapp/db", 3, "password")
```

---

## Reproducible builds & secret versioning

**Why mandatory versioning matters:** apps are long-lived and must be
rebuildable from any past release — for hotfixes, audits, or store resubmissions.
If config read "the latest secret," rebuilding last year's app would bake in
*this year's* secret values, producing an artifact that never existed and may not
even work. Pinning the KV v2 version makes the build **reproducible**: the secret
revision is part of the versioned source.

**The discipline:** pin the version in the git-tracked Pkl when you cut a release.
Then `git checkout <old-tag>` reproduces the app code **and** its exact secret
versions together — one source of truth, one deterministic result.

**How it is enforced** (no separate static-analysis step required):

1. **Structurally, by Pkl.** The `vaultField` / `vaultSecret` helpers take a
   required `version: Int`. Pkl will not type-check a call that omits it, so the
   mistake is caught before anything runs.
2. **At evaluation time, by the reader.** Even a hand-written raw
   `read("vault:...")` without `?version=` is **rejected** by the pkl-vault
   reader during evaluation, failing the build with a clear error.

Together these mean an unversioned secret read simply cannot make it into a
built artifact.

---

## Configuration

The reader is configured entirely by environment variables — nothing secret
ever lives in the repo:

| Variable | Required | Purpose |
|---|---|---|
| `VAULT_ADDR` | ✅ | Base address, e.g. `https://vault.example.com` |
| `VAULT_TOKEN` | — on local builds | Token sent as `X-Vault-Token` (use a short-TTL token) |
| `VAULT_NAMESPACE` | — | Vault Enterprise namespace (`X-Vault-Namespace`) |

**Token resolution** (env always wins):

1. `VAULT_TOKEN` if set — **mandatory in CI**, where there is no interactive login.
2. Otherwise the `~/.vault-token` helper file written by `vault login` — this is
   how **local builds** authenticate without putting a token in the build env:
   the developer runs `vault login` once in their terminal, and the build reuses
   that cached token.
3. Otherwise **fail-closed** with a clear error.

**Fail-closed:** if `VAULT_ADDR` is missing, or no token can be found by either
route, evaluation stops with a clear error rather than silently producing config
without secrets.

---

## Usage — step by step

### Prerequisites (all surfaces)

1. A Vault server reachable from the build, with KV v2 secrets engine.
2. A token with a policy granting read on the paths you reference.
3. Credentials available to the build:
   - **CI:** `VAULT_ADDR` + `VAULT_TOKEN` from the CI secret store.
   - **Local:** `VAULT_ADDR` set, then `vault login` once in your terminal —
     the build picks up the cached `~/.vault-token`, so no token needs to live
     in your build environment.

Quick sanity check from a shell:

```bash
export VAULT_ADDR="https://vault.example.com"
vault login                                  # local: caches a token in ~/.vault-token
# (CI instead sets: export VAULT_TOKEN="s.xxxxxxxx"  # short-TTL)
vault kv get -mount=secret myapp/db          # confirms the path + policy
```

### Android (Kotlin, Gradle)

1. Apply the Pkl Gradle plugin and add the `pkl-vault` JVM artifact:

   ```kotlin
   // build.gradle.kts
   plugins {
     id("org.pkl-lang") version "0.32.1"
   }
   dependencies {
     pklTools("de.starfinanz.pkl.vault:pkl-vault-kmp-jvm:0.1.0")
   }
   ```

2. Register the reader on the Pkl codegen task:

   ```kotlin
   pkl {
     kotlinCodeGenerators {
       register("appConfig") {
         sourceModules.add("src/main/pkl/app.pkl")
         // pkl-vault contributes the `vault:` ResourceReader here
         resourceReaders.add(de.starfinanz.pkl.vault.PklVaultReader.fromEnvironment())
       }
     }
   }
   ```

3. Provide `VAULT_ADDR` + `VAULT_TOKEN` in the build environment.
4. Run the build. Pkl generates typed Kotlin with secrets resolved; your app
   compiles them in. **Promon** encrypts the bundle post-build.

### iOS (Swift, SPM / Xcode)

1. Add the Pkl Swift package and the SPM build plugin that generates Swift
   from your Pkl modules.
2. Register the Swift adapter on the evaluator used for codegen:

   ```swift
   import PklVault   // thin Swift shim over the shared Kotlin/Native framework

   let evaluator = try EvaluatorManager().newEvaluator(
     options: .preconfigured.withResourceReader(VaultResourceReader())
   )
   ```

3. Expose `VAULT_ADDR` + `VAULT_TOKEN` to the build (Keychain / CI).
4. Build. Generated Swift config is compiled into the app; **Promon** encrypts
   the bundle post-build.

### CI / server (embedded JVM reader)

The supported CI route today is the **embedded JVM reader**: register
`PklVaultReader` on an in-process Pkl evaluator (the exact path the JVM
integration test exercises), render your config, and treat the output as an
ephemeral artifact.

```kotlin
// render.kts (or a small Gradle/JVM step on the CI jar)
import org.pkl.core.EvaluatorBuilder
import org.pkl.core.ModuleSource
import java.util.regex.Pattern
import de.starfinanz.pkl.vault.PklVaultReader

PklVaultReader.fromEnvironment().use { reader ->
  EvaluatorBuilder.preconfigured()
    .let { it.setAllowedResources(it.allowedResources + Pattern.compile("vault:.*")) }
    .addResourceReader(reader)
    .build()
    .use { evaluator ->
      val json = evaluator.evaluateOutputText(ModuleSource.path("config.pkl"))
      java.io.File("build/config.json").writeText(json) // ephemeral, .gitignore'd
    }
}
```

CI injects a **short-TTL** `VAULT_TOKEN`; the rendered file is `.gitignore`d and
never echoed to logs.

> **Native external-reader executable (`pkl eval --external-resource-reader`)
> is deferred.** It needs a hand-rolled MessagePack stdio protocol in
> Kotlin/Native (no official SDK) and a new dependency; until there is a CI need
> it can't meet, CI uses the embedded JVM reader above.

---

## Security model

- **Auth is isolated** in one place. Token today; swapping to AppRole or
  Kubernetes auth later touches only how the token is obtained — **no Pkl or
  app-code changes**.
- **Short-TTL tokens**, and **fail-closed** when `VAULT_TOKEN`/`VAULT_ADDR` are
  absent.
- **Redaction is structural:** the reader never logs secret values, and secret
  values are wrapped so they cannot be printed by accident.
- **At rest in the bundle:** eval-time baking writes resolved secrets into the
  compiled app. On mobile this is normally unsafe, but **Promon app shielding
  encrypts the bundled files after the binary is built and decrypts them at
  runtime**, so baked config is protected at rest.
- **Non-bundle outputs** (CI-rendered server config) are treated as sensitive:
  ephemeral files, `.gitignore`d, never logged.
- **Optional target guard:** a per-project `target = app | server` flag and a
  `clientSafe` schema marker let teams keep specific high-value secrets out of
  client builds.

> **Residual guidance:** secrets are still decrypted in memory at runtime. For
> the very highest-value secrets, prefer a backend/BFF where one already exists.

---

## Repository layout

```
pkl-vault-kmp/                 # the Kotlin Multiplatform library
├─ src/commonMain/kotlin/…     # portable core
│   ├─ VaultUri.kt             # parse "vault:secret/data/app#key"
│   ├─ VaultClient.kt          # Ktor: GET KV v2, X-Vault-Token header
│   ├─ VaultResolver.kt        # uri → bytes; cache; fail-closed; target guard
│   ├─ VaultEnv.kt             # VAULT_ADDR / VAULT_TOKEN / VAULT_NAMESPACE
│   └─ Redaction.kt            # never log secret values
├─ src/jvmMain/kotlin/…        # Android + CI (JVM)
│   └─ PklVaultReader.kt       # org.pkl.core.resource.ResourceReader
├─ src/nativeMain/kotlin/…     # iOS + macOS
│   └─ PklVaultBridge.kt       # sync entry point for the Swift adapter
├─ swift-adapter/              # tiny Swift shim over the Kotlin/Native framework
│   └─ VaultResourceReader.swift
company.config/                # shared Pkl package: AppConfig.pkl, secrets.pkl
Jenkinsfile                    # CI: build JVM jar + native artifacts on macOS
```

---

## Quick start for a new project (summary)

1. Depend on the shared Pkl package `company.config` and write your `app.pkl`.
2. Reference secrets with the typed helper, e.g.
   `secrets.vaultField("secret", "myapp/db", 3, "password")` — the `version` is
   required, so builds stay reproducible.
3. Register the `pkl-vault` reader for your platform (Gradle / SPM / CLI).
4. Provide `VAULT_ADDR` + `VAULT_TOKEN` in the build environment.
5. Build — typed config is generated with secrets resolved, and protected at
   rest by Promon (mobile) or treated as ephemeral (server).

---

## Implementation status

This README describes the full blueprint. Current state of the codebase:

| Milestone | Status |
|---|---|
| M0 — KMP scaffolding (Gradle, targets, CI) | ✅ Implemented |
| M1 — Shared core (`VaultUri`, `VaultClient`, `VaultResolver`, redaction) | ✅ Implemented + tested |
| M2 — Bindings (JVM reader ✅ tested; Swift adapter + framework export; native external reader deferred) | 🟡 Partially implemented |
| M3 — Shared `company.config` Pkl package | ⏳ Planned |
| M4 — Pilots (iOS, Android, one CI service) | ⏳ Planned |
| M5 — Promon pipeline + docs hardening | ⏳ Planned |

The core that talks to Vault (parse, fetch, cache, fail-closed, redact) is built
and tested on JVM, macOS and iOS targets. The platform wiring in the usage
recipes above is the next milestone.

---

## References

- Pkl language & resources — https://pkl-lang.org
- Pkl Swift bindings — https://pkl-lang.org/swift/current/
- HashiCorp Vault KV v2 API — https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2
- Ktor multiplatform client — https://ktor.io/docs/client-create-multiplatform-application.html
