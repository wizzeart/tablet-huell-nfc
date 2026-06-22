# CI — Build automático del APK

El workflow [`build-apk.yml`](workflows/build-apk.yml) compila el APK **release firmado**:

| Evento | Qué hace |
|---|---|
| `push` a `master` (remote `github`) | Publica **GitHub Release** (`latest`) + sube **artifact**. |
| **Pull Request** a `master` | Solo compila y sube **artifact** (no publica Release; el firmado es opcional). |
| **Run workflow** (manual, pestaña Actions) | Igual que push: Release + artifact. |

El APK queda en la pestaña **Releases** del repo, instalable encima de la versión anterior en las
tablets (mismo keystore ⇒ se actualiza sin desinstalar). Cada build incrementa `versionCode` con el
número de corrida. El artifact también se descarga desde la pestaña **Actions** (caduca a 90 días).

> **Nota sobre minify:** el `buildType release` tiene `minifyEnabled false` para que el APK
> auto-publicado se comporte igual que el debug ya probado, sin riesgo de que R8/ProGuard rompa
> reflexión/JNI en silencio. Las keep rules de `proguard-rules.pro` quedan listas por si se reactiva
> tras validar un build minificado en una tablet real.

## Secrets a configurar (una sola vez)

`Settings → Secrets and variables → Actions → New repository secret`:

| Secret | Contenido |
|---|---|
| `SECRETS_PROPERTIES` | El contenido **completo** de tu `secrets.properties` local (todas las líneas DB_*, PETRONOVA_*, ADMIN_PASSWORD, EXIT_PIN). |
| `PRIVATE_KEY_PEM` | El contenido completo de `app/src/main/assets/private_key.pem` (clave RSA). |
| `SIGNING_KEYSTORE_BASE64` | El keystore `.jks` en base64 (ver abajo). |
| `SIGNING_KEYSTORE_PASSWORD` | Password del store. |
| `SIGNING_KEY_ALIAS` | Alias de la clave. |
| `SIGNING_KEY_PASSWORD` | Password de la clave. |

> `GITHUB_TOKEN` lo provee GitHub automáticamente; no hay que crearlo.

## Crear el keystore (una sola vez)

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias petronova \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass TU_STORE_PASS -keypass TU_KEY_PASS \
  -dname "CN=Petronova Kiosk, O=Petronova, C=BO"
```

Conviértelo a base64 para el secret:

```bash
# Linux / Git Bash
base64 -w0 release.jks > release.jks.b64
```
```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content release.jks.b64
```

Pega el contenido de `release.jks.b64` en `SIGNING_KEYSTORE_BASE64`, y usa `petronova`,
`TU_STORE_PASS`, `TU_KEY_PASS` en los otros tres secrets.

> ⚠️ **Guarda `release.jks` en un lugar seguro y NO lo pierdas.** Si se pierde, no podrás volver a
> firmar actualizaciones con la misma identidad y habría que desinstalar/reinstalar en cada tablet.
> El `.jks` está en `.gitignore` — nunca se sube al repo.

## Build local (opcional)

Para firmar release en tu máquina sin variables de entorno, crea `keystore.properties` en la raíz
(gitignoreado):

```properties
storeFile=release.jks
storePassword=TU_STORE_PASS
keyAlias=petronova
keyPassword=TU_KEY_PASS
```

Si no hay keystore, `assembleRelease` genera el APK **sin firmar** (no instalable); para desarrollo
usa `assembleDebug`.
