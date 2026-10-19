# pkl-vault — typisierte App-Konfiguration mit Vault-Secrets, ein Codebase für jede Plattform

> **Die Idee in einem Satz:** Schreibe deine Anwendungskonfiguration einmal in
> [Pkl](https://pkl-lang.org) — typisiert, validiert, versioniert in Git — und
> lass die geheimen Werte zur Build-Zeit direkt aus **HashiCorp Vault** kommen,
> über eine einzige **Kotlin-Multiplatform**-Bibliothek, die sich in iOS,
> Android und CI/CD einklinkt.

---

## Warum es das gibt

Jedes Projekt erfindet den Umgang mit Konfiguration und Secrets neu: `.env`-
Dateien, Plist-Hacks, hartcodierte Schlüssel, ein anderer Mechanismus pro
Plattform. Das ist fehleranfällig, untypisiert und schleust Secrets in Git und
in die App-Bundles.

`pkl-vault` ersetzt all das durch **einen** Ansatz:

| Problem heute | Mit pkl-vault |
|---|---|
| Konfiguration sind untypisierte Strings, über Plattformen verstreut | Ein **typisiertes, validiertes** Pkl-Schema, firmenweit geteilt |
| Secrets per Copy-Paste in `.env` / Plists / Quellcode | Secrets werden aus **Vault** gelesen, nie in Git gespeichert |
| Eine andere Integration pro Plattform/Sprache | **Ein Kotlin-Codebase**; dünne Adapter für iOS, Android, CLI |
| „Ist dieser Wert ein Secret?" ist Stammeswissen | Secrets sind im Schema explizit und werden abgesichert |

**Festgelegte Entscheidungen** (das Fundament des Designs):

| Thema | Entscheidung |
|---|---|
| Integrationsstil | Eigener Pkl-`vault:`-Resource-Reader |
| Implementierungssprache | Kotlin Multiplatform (gemeinsamer Kern + dünne Adapter) |
| Zeitpunkt der Secret-Auflösung | **Eval-Zeit** — zur Build-Zeit in die generierte Konfiguration eingebacken |
| Vault-Authentifizierung | **Token** (`VAULT_ADDR` + `VAULT_TOKEN`), isoliert für späteren Austausch |
| Plattformen | iOS (Swift) + Android (Kotlin) + CI |
| Bundle-Schutz | **Promon** schützt/verschlüsselt gebündelte Dateien nach dem Build |

---

## Wie es funktioniert (die 60-Sekunden-Version)

Du schreibst ganz normales Pkl und liest ein Secret über eine `vault:`-URI:

```pkl
// app.pkl
amends "company.config.AppConfig"

apiBaseUrl = "https://api.example.com"
database {
  host = "db.internal"
  // Zur Build-Zeit aus Vault geholt, auf Secret-Version 3 fixiert:
  password = secrets.vaultField("secret", "myapp/db", 3, "password")
}
```

Wenn Pkl diese Datei auswertet, fängt unser **`vault:`-Resource-Reader** den
Lesevorgang ab, holt das Secret per HTTPS aus Vault und liefert den Wert zurück.
Pkl generiert daraus typisiertes Swift/Kotlin, das deine App einkompiliert.

```
┌────────────┐   read("vault:…")   ┌───────────────┐   HTTPS GET /v1/…   ┌─────────┐
│  dein .pkl │ ──────────────────► │ pkl-vault      │ ──────────────────► │  Vault  │
│  Konfig    │                     │ Resource-Reader│   X-Vault-Token     │  KV v2  │
└────────────┘ ◄────────────────── └───────────────┘ ◄────────────────── └─────────┘
      │       aufgelöster Wert
      ▼
 generiertes Swift / Kotlin  ──►  in die App kompiliert  ──►  Promon-verschlüsseltes Bundle
```

**Eine Bibliothek, vier Modi** — derselbe Kotlin-Kern treibt alle an:

| Oberfläche | Mechanismus | Artefakt |
|---|---|---|
| Android-Build | Reader auf dem Pkl-Gradle-Task registriert | JVM-Jar |
| iOS-Build | Swift-Adapter → gemeinsames Kotlin/Native-Framework | iOS-Framework |
| CI / Server | Eingebettete JVM **oder** externe Reader-Executable | JVM-Jar / Native-Exe |
| Beliebige `pkl`-CLI | `--external-resource-reader vault=./pkl-vault` | Native-Executable |

---

## Das `vault:`-URI-Format

```
vault:<mount>/data/<path>?version=<n>#<field>
      └─ KV-v2-Mount       │          └─ optionaler Feld-Selektor
                           └─ ERFORDERLICHE KV-v2-Version
```

- `vault:secret/data/myapp/db?version=3#password` → das Feld `password` von
  **Version 3** dieses Secrets.
- `vault:secret/data/myapp/db?version=3` → das **gesamte** Secret-Objekt
  (Version 3) als JSON.

Zwei Teile sind erforderlich:

- Das wörtliche `data`-Segment — es spiegelt den echten Vault-KV-v2-API-Pfad
  wider, sodass die URI eine transparente Abbildung der tatsächlich
  ausgeführten Anfrage ist.
- Die `?version=<n>`-Query — **Versionierung ist verpflichtend** (siehe
  [Reproduzierbare Builds](#reproduzierbare-builds--secret-versionierung)). Eine
  unversionierte `vault:`-URI wird zur Auswertungszeit abgelehnt, sodass ein
  Build niemals stillschweigend ein neueres Secret übernimmt als das, mit dem er
  ausgeliefert wurde.

Nutze den typisierten Helfer, statt URIs von Hand zu schreiben — er macht die
Version **unmöglich zu vergessen**, weil Pkl das `version`-Argument verlangt:

```pkl
// secrets.pkl (Bibliotheks-Helfer) — `version` ist ein Pflichtparameter
function vaultField(mount: String, path: String, version: Int, field: String): String =
  read("vault:\(mount)/data/\(path)?version=\(version)#\(field)").text

// Verwendung
password = vaultField("secret", "myapp/db", 3, "password")
```

---

## Reproduzierbare Builds & Secret-Versionierung

**Warum verpflichtende Versionierung wichtig ist:** Apps sind langlebig und
müssen aus jedem früheren Release neu baubar sein — für Hotfixes, Audits oder
Store-Resubmissions. Würde die Konfiguration „das neueste Secret" lesen, würde
ein Neubau der letztjährigen App die *diesjährigen* Secret-Werte einbacken und
ein Artefakt erzeugen, das es nie gab und das womöglich gar nicht funktioniert.
Das Fixieren der KV-v2-Version macht den Build **reproduzierbar**: Die
Secret-Revision ist Teil des versionierten Quellcodes.

**Die Disziplin:** Fixiere die Version im Git-versionierten Pkl, wenn du ein
Release schneidest. Dann reproduziert `git checkout <old-tag>` den App-Code
**und** seine exakten Secret-Versionen gemeinsam — eine Quelle der Wahrheit, ein
deterministisches Ergebnis.

**Wie es durchgesetzt wird** (ohne separaten statischen Analyseschritt):

1. **Strukturell, durch Pkl.** Die Helfer `vaultField` / `vaultSecret` erwarten
   ein verpflichtendes `version: Int`. Pkl type-checkt keinen Aufruf, der es
   weglässt — der Fehler wird erkannt, bevor überhaupt etwas läuft.
2. **Zur Auswertungszeit, durch den Reader.** Selbst ein von Hand geschriebenes
   rohes `read("vault:...")` ohne `?version=` wird vom pkl-vault-Reader während
   der Auswertung **abgelehnt** und bricht den Build mit einer klaren
   Fehlermeldung ab.

Zusammen bedeuten sie, dass ein unversionierter Secret-Read schlicht nicht in ein
gebautes Artefakt gelangen kann.

---

## Konfiguration

Der Reader wird vollständig über Umgebungsvariablen konfiguriert — nichts
Geheimes lebt jemals im Repository:

| Variable | Erforderlich | Zweck |
|---|---|---|
| `VAULT_ADDR` | ✅ | Basis-Adresse, z. B. `https://vault.example.com` |
| `VAULT_TOKEN` | — bei lokalen Builds | Token, gesendet als `X-Vault-Token` (Token mit kurzer TTL nutzen) |
| `VAULT_NAMESPACE` | — | Vault-Enterprise-Namespace (`X-Vault-Namespace`) |

**Token-Auflösung** (Umgebungsvariable hat immer Vorrang):

1. `VAULT_TOKEN`, falls gesetzt — **verpflichtend in CI**, wo es kein
   interaktives Login gibt.
2. Andernfalls die von `vault login` geschriebene Helper-Datei `~/.vault-token`
   — so authentifizieren sich **lokale Builds**, ohne ein Token in der
   Build-Umgebung abzulegen: Der Entwickler führt einmal `vault login` im
   Terminal aus, und der Build verwendet dieses zwischengespeicherte Token wieder.
3. Andernfalls **Fail-closed** mit einer klaren Fehlermeldung.

**Fail-closed:** Fehlt `VAULT_ADDR` oder lässt sich über keinen der beiden Wege
ein Token finden, bricht die Auswertung mit einer klaren Fehlermeldung ab, statt
stillschweigend eine Konfiguration ohne Secrets zu erzeugen.

---

## Verwendung — Schritt für Schritt

### Voraussetzungen (alle Oberflächen)

1. Ein vom Build erreichbarer Vault-Server mit aktivierter KV-v2-Secrets-Engine.
2. Ein Token mit einer Policy, die Lesezugriff auf die referenzierten Pfade gewährt.
3. Zugangsdaten für den Build verfügbar:
   - **CI:** `VAULT_ADDR` + `VAULT_TOKEN` aus dem CI-Secret-Store.
   - **Lokal:** `VAULT_ADDR` setzen, dann einmal `vault login` im Terminal —
     der Build übernimmt das zwischengespeicherte `~/.vault-token`, sodass kein
     Token in deiner Build-Umgebung liegen muss.

Schneller Funktionstest aus einer Shell:

```bash
export VAULT_ADDR="https://vault.example.com"
vault login                                  # lokal: speichert ein Token in ~/.vault-token
# (CI setzt stattdessen: export VAULT_TOKEN="s.xxxxxxxx"  # kurze TTL)
vault kv get -mount=secret myapp/db          # prüft Pfad + Policy
```

### Android (Kotlin, Gradle)

1. Wende das Pkl-Gradle-Plugin an und füge das `pkl-vault`-JVM-Artefakt hinzu:

   ```kotlin
   // build.gradle.kts
   plugins {
     id("org.pkl-lang") version "0.32.1"
   }
   dependencies {
     pklTools("de.starfinanz.pkl.vault:pkl-vault-kmp-jvm:0.1.0")
   }
   ```

2. Registriere den Reader auf dem Pkl-Codegen-Task:

   ```kotlin
   pkl {
     kotlinCodeGenerators {
       register("appConfig") {
         sourceModules.add("src/main/pkl/app.pkl")
         // pkl-vault steuert hier den `vault:`-ResourceReader bei
         resourceReaders.add(de.starfinanz.pkl.vault.PklVaultReader())
       }
     }
   }
   ```

3. Stelle `VAULT_ADDR` + `VAULT_TOKEN` in der Build-Umgebung bereit.
4. Führe den Build aus. Pkl generiert typisiertes Kotlin mit aufgelösten
   Secrets; deine App kompiliert es ein. **Promon** verschlüsselt das Bundle
   nach dem Build.

### iOS (Swift, SPM / Xcode)

1. Füge das Pkl-Swift-Package und das SPM-Build-Plugin hinzu, das Swift aus
   deinen Pkl-Modulen generiert.
2. Registriere den Swift-Adapter auf dem für die Codegenerierung genutzten
   Evaluator:

   ```swift
   import PklVault   // dünner Swift-Shim über dem gemeinsamen Kotlin/Native-Framework

   let evaluator = try EvaluatorManager().newEvaluator(
     options: .preconfigured.withResourceReader(VaultResourceReader())
   )
   ```

3. Mache `VAULT_ADDR` + `VAULT_TOKEN` für den Build verfügbar (Keychain / CI).
4. Baue. Die generierte Swift-Konfiguration wird in die App kompiliert;
   **Promon** verschlüsselt das Bundle nach dem Build.

### CI / Server (Pkl-CLI, externer Reader)

1. Liefere die native External-Reader-Executable (oder das JVM-Jar) mit deiner
   Pipeline aus.
2. Werte aus und binde den Reader über das Schema ein:

   ```bash
   pkl eval \
     --external-resource-reader vault=./pkl-vault \
     config.pkl --format json --output-path build/config.json
   ```

3. CI injiziert ein `VAULT_TOKEN` mit **kurzer TTL**; rendere in ein
   **flüchtiges** Artefakt, das per `.gitignore` ausgeschlossen und nie in Logs
   ausgegeben wird.

---

## Sicherheitsmodell

- **Authentifizierung ist an einer Stelle isoliert.** Heute Token; der spätere
  Wechsel zu AppRole- oder Kubernetes-Auth betrifft nur, wie das Token bezogen
  wird — **keine Änderungen an Pkl oder App-Code**.
- **Tokens mit kurzer TTL** und **Fail-closed**, wenn `VAULT_TOKEN`/`VAULT_ADDR`
  fehlen.
- **Redaction ist strukturell:** Der Reader loggt niemals Secret-Werte, und
  Secret-Werte sind so gekapselt, dass sie nicht versehentlich ausgegeben werden
  können.
- **Im Bundle (at rest):** Das Einbacken zur Eval-Zeit schreibt aufgelöste
  Secrets in die kompilierte App. Auf Mobilgeräten ist das normalerweise unsicher,
  aber **Promon App-Shielding verschlüsselt die gebündelten Dateien nach dem Build
  und entschlüsselt sie zur Laufzeit**, sodass eingebackene Konfiguration at rest
  geschützt ist.
- **Nicht-Bundle-Ausgaben** (vom CI gerenderte Server-Konfiguration) werden als
  sensibel behandelt: flüchtige Dateien, per `.gitignore` ausgeschlossen, nie
  geloggt.
- **Optionaler Target-Guard:** Ein projektweites Flag `target = app | server` und
  ein `clientSafe`-Schema-Marker erlauben es Teams, bestimmte besonders
  wertvolle Secrets aus Client-Builds herauszuhalten.

> **Verbleibender Hinweis:** Secrets werden zur Laufzeit weiterhin im Speicher
> entschlüsselt. Für die allerhöchstwertigen Secrets ist ein Backend/BFF
> vorzuziehen, wo bereits eines existiert.

---

## Repository-Aufbau

```
pkl-vault-kmp/                 # die Kotlin-Multiplatform-Bibliothek
├─ src/commonMain/kotlin/…     # portabler Kern
│   ├─ VaultUri.kt             # parse "vault:secret/data/app#key"
│   ├─ VaultClient.kt          # Ktor: GET KV v2, X-Vault-Token-Header
│   ├─ VaultResolver.kt        # URI → Bytes; Cache; fail-closed; Target-Guard
│   ├─ VaultEnv.kt             # VAULT_ADDR / VAULT_TOKEN / VAULT_NAMESPACE
│   └─ Redaction.kt            # loggt niemals Secret-Werte
├─ src/jvmMain/kotlin/…        # Android + CI (JVM)
│   └─ PklVaultReader.kt       # org.pkl.core.resource.ResourceReader
├─ src/nativeMain/kotlin/…     # iOS + macOS + Linux
│   └─ ExternalReaderMain.kt   # Pkl-External-Reader-stdio-Protokoll
swift-adapter/                 # kleiner Swift-Shim über dem Kotlin/Native-Framework
company.config/                # gemeinsames Pkl-Package: AppConfig.pkl, secrets.pkl
Jenkinsfile                    # CI: baut JVM-Jar + native Artefakte auf macOS
```

---

## Schnellstart für ein neues Projekt (Zusammenfassung)

1. Hänge am gemeinsamen Pkl-Package `company.config` und schreibe deine `app.pkl`.
2. Referenziere Secrets über den typisierten Helfer, z. B.
   `secrets.vaultField("secret", "myapp/db", 3, "password")` — die `version` ist
   verpflichtend, sodass Builds reproduzierbar bleiben.
3. Registriere den `pkl-vault`-Reader für deine Plattform (Gradle / SPM / CLI).
4. Stelle `VAULT_ADDR` + `VAULT_TOKEN` in der Build-Umgebung bereit.
5. Baue — typisierte Konfiguration wird mit aufgelösten Secrets generiert und at
   rest durch Promon (Mobile) geschützt bzw. als flüchtig behandelt (Server).

---

## Implementierungsstatus

Diese README beschreibt den vollständigen Blueprint. Aktueller Stand des Codebase:

| Meilenstein | Status |
|---|---|
| M0 — KMP-Scaffolding (Gradle, Targets, CI) | ✅ Umgesetzt |
| M1 — Gemeinsamer Kern (`VaultUri`, `VaultClient`, `VaultResolver`, Redaction) | ✅ Umgesetzt + getestet |
| M2 — Bindings (JVM-Reader, nativer External-Reader, Swift-Adapter) | ⏳ Geplant |
| M3 — Gemeinsames `company.config`-Pkl-Package | ⏳ Geplant |
| M4 — Piloten (iOS, Android, ein CI-Service) | ⏳ Geplant |
| M5 — Promon-Pipeline + Doku-Härtung | ⏳ Geplant |

Der Kern, der mit Vault spricht (parsen, holen, cachen, fail-closed, redacten),
ist gebaut und auf JVM-, macOS- und iOS-Targets getestet. Die Plattform-Verdrahtung
in den obigen Verwendungs-Rezepten ist der nächste Meilenstein.

---

## Referenzen

- Pkl-Sprache & Ressourcen — https://pkl-lang.org
- Pkl-Swift-Bindings — https://pkl-lang.org/swift/current/
- HashiCorp-Vault-KV-v2-API — https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2
- Ktor-Multiplatform-Client — https://ktor.io/docs/client-create-multiplatform-application.html
