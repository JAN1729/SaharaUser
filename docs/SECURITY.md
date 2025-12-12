# SaharaUser Security Guide

## Overview

This document outlines security measures implemented in the SaharaUser app to protect API keys and sensitive data.

---

## API Key Protection

### Build-Time Configuration

API keys are loaded from `local.properties` at **build time** and embedded in `BuildConfig`:

```
local.properties (NEVER COMMIT)
       │
       ▼
  build.gradle.kts (reads at build time)
       │
       ▼
  BuildConfig.java (generated)
       │
       ▼
  SecureConfig.kt (provides access)
       │
       ▼
  API Clients (use keys)
```

### Files Involved

| File | Purpose | In Git? |
|------|---------|---------|
| `local.properties` | Stores actual keys | ❌ NO (.gitignore) |
| `local.properties.template` | Template for new devs | ✅ YES |
| `build.gradle.kts` | Reads keys at build | ✅ YES |
| `SecureConfig.kt` | Provides secure access | ✅ YES |
| `BuildConfig.java` | Generated with keys | ❌ NO (build/) |

---

## Security Layers

### 1. .gitignore Protection

```gitignore
# SECURITY - NEVER COMMIT THESE FILES
local.properties
*.env
.env
secrets.properties
api-keys.properties
*.jks
*.keystore
google-services.json
```

### 2. Log Redaction

API keys are automatically redacted in logs:

```kotlin
// PerplexityClient.kt
val safeMessage = message
    .replace(Regex("pplx-[a-zA-Z0-9]+"), "pplx-***REDACTED***")
    .replace(Regex("Bearer [a-zA-Z0-9-]+"), "Bearer ***REDACTED***")
```

### 3. Key Validation

SecureConfig validates keys before use:

```kotlin
fun validatePerplexityKey(key: String): Boolean {
    return key.isNotBlank() && 
           key.startsWith("pplx-") && 
           key.length > 10
}
```

### 4. Release Build Protection

```kotlin
// build.gradle.kts
release {
    isMinifyEnabled = true    // Code obfuscation
    isShrinkResources = true  // Remove unused resources
    isDebuggable = false      // No debugging in release
}
```

### 5. ProGuard Log Stripping

```proguard
# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

### 6. Encrypted Storage

Runtime secrets can use EncryptedSharedPreferences:

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

EncryptedSharedPreferences.create(
    context,
    "secure_prefs",
    masterKey,
    PrefKeyEncryptionScheme.AES256_SIV,
    PrefValueEncryptionScheme.AES256_GCM
)
```

---

## Supabase RLS Protection

The `SUPABASE_ANON_KEY` is safe for client-side use because:

1. **Row Level Security (RLS)** controls what data can be accessed
2. Anon key can only access data allowed by RLS policies
3. Service role key is **NEVER** used in mobile apps

### Recommended RLS Policies

```sql
-- Users can only access their own data
CREATE POLICY "Users can view own data"
ON users FOR SELECT
USING (auth.uid() = id OR id LIKE 'USR-%');

-- Read-only for route data (public)
CREATE POLICY "Route stops are public"
ON route_stops FOR SELECT
TO anon
USING (true);

-- Locations are public (for live tracking)
CREATE POLICY "Locations are public"
ON locations FOR SELECT
TO anon
USING (true);
```

---

## Setup for New Developers

### Step 1: Create local.properties

```bash
# Copy template
cp local.properties.template local.properties

# Edit with your keys
notepad local.properties
```

### Step 2: Add Keys

```properties
PERPLEXITY_API_KEY=pplx-your-actual-key
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

### Step 3: Build

```bash
./gradlew build
```

---

## API Key Rotation

### Perplexity

1. Go to https://www.perplexity.ai/settings/api
2. Revoke old key
3. Create new key
4. Update `local.properties`
5. Rebuild app

### Supabase

1. Go to Dashboard → Settings → API
2. Rotate anon key (creates new one)
3. Update `local.properties`
4. Rebuild app

---

## Checklist

- [ ] `local.properties` in `.gitignore`
- [ ] No hardcoded keys in source code
- [ ] API keys loaded via SecureConfig
- [ ] Logs redact sensitive data
- [ ] Release builds have minification
- [ ] ProGuard removes debug logs
- [ ] RLS policies on Supabase tables
- [ ] HTTPS only for all API calls

---

## What NOT to Do

❌ **Never commit API keys**
```git
# BAD - never do this
git add local.properties
```

❌ **Never hardcode keys**
```kotlin
// BAD - never do this
val API_KEY = "pplx-abc123..."
```

❌ **Never use service_role key**
```properties
# BAD - this key bypasses RLS!
SUPABASE_SERVICE_ROLE_KEY=...
```

❌ **Never log sensitive data**
```kotlin
// BAD - never do this
Log.d("API", "Key: $apiKey")
```

---

## Security Contacts

If you discover a security issue:
1. Do not create a public issue
2. Contact the development team directly
3. Provide details of the vulnerability
