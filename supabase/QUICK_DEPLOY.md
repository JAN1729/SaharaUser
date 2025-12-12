# Quick Deployment Instructions

Since the Supabase CLI needs special installation on Windows, here's the recommended approach:

## Option 1: Install via Scoop (Recommended)

1. **Install Scoop** (if not already installed):
   ```powershell
   # Run in PowerShell as Administrator
   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
   irm get.scoop.sh | iex
   ```

2. **Install Supabase CLI**:
   ```powershell
   scoop bucket add supabase https://github.com/supabase/scoop-bucket.git
   scoop install supabase
   ```

3. **Run the deployment script**:
   ```powershell
   cd D:\SaharaUser\supabase
   .\deploy.bat
   ```

   The script will guide you through:
   - Login (opens browser)
   - Project linking (needs your Project Reference ID)
   - API key setup (needs your Perplexity key)
   - Deploying all 3 functions

## Option 2: Manual Commands

If you prefer to run commands manually:

```powershell
# 1. Login
supabase login

# 2. Link project (get YOUR_PROJECT_REF from Supabase dashboard)
cd D:\SaharaUser
supabase link --project-ref YOUR_PROJECT_REF

# 3. Set API key
supabase secrets set PERPLEXITY_API_KEY=your_key_here

# 4. Deploy functions
supabase functions deploy process-query --no-verify-jwt
supabase functions deploy track-bus --no-verify-jwt
supabase functions deploy get-nearby-stops --no-verify-jwt
```

## What You Need

Before running deployment:

1. **Supabase Project Reference ID**
   - Go to: https://supabase.com/dashboard
   - Open your project
   - Settings → General → Reference ID

2. **Perplexity API Key**
   - Go to: https://www.perplexity.ai/settings/api
   - Create a new API key

3. **Supabase Anon Key** (for Android app)
   - Go to: Supabase Dashboard → Settings → API
   - Copy the `anon` / `public` key

## After Deployment

Update `D:\SaharaUser\local.properties`:

```properties
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.anon.key=YOUR_ANON_KEY
```

## Test Deployment

```powershell
# Test with cURL (PowerShell)
$headers = @{
    "Authorization" = "Bearer YOUR_ANON_KEY"
    "Content-Type" = "application/json"
}

$body = @{
    userId = "test_user"
    query = "How do I get from MG Road to Electronic City?"
} | ConvertTo-Json

Invoke-RestMethod -Uri "https://YOUR_PROJECT_REF.supabase.co/functions/v1/process-query" `
    -Method Post `
    -Headers $headers `
    -Body $body
```
