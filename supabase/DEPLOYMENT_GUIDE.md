# Supabase Edge Functions Deployment Guide

Complete step-by-step guide to deploy the multi-agent system to Supabase.

---

## Prerequisites

- Supabase account (sign up at https://supabase.com)
- Perplexity API key (from https://www.perplexity.ai/settings/api)
- Node.js installed (v18 or higher)

---

## Step 1: Install Supabase CLI

Open PowerShell and run:

```powershell
npm install -g supabase
```

Verify installation:

```powershell
supabase --version
```

You should see something like: `1.x.x`

---

## Step 2: Login to Supabase

```powershell
supabase login
```

This will:
1. Open your browser
2. Ask you to authorize the CLI
3. Return you to the terminal once authorized

---

## Step 3: Link to Your Supabase Project

### Option A: Link to Existing Project

If you already have a Supabase project:

```powershell
cd D:\SaharaUser
supabase link --project-ref YOUR_PROJECT_REF
```

**How to find your PROJECT_REF:**
1. Go to https://supabase.com/dashboard
2. Open your project
3. Go to Settings → General
4. Copy the "Reference ID"

### Option B: Create New Project

If you don't have a project yet:

1. Go to https://supabase.com/dashboard
2. Click "New Project"
3. Fill in:
   - Name: `SaharaUser` (or your choice)
   - Database Password: (create a strong password - SAVE THIS!)
   - Region: Choose closest to your users (e.g., `ap-south-1` for India)
4. Click "Create new project"
5. Wait 2-3 minutes for setup
6. Once ready, get the Reference ID and run:

```powershell
cd D:\SaharaUser
supabase link --project-ref YOUR_PROJECT_REF
```

When prompted for the database password, enter the one you created above.

---

## Step 4: Set API Key as Secret

Your Perplexity API key must be stored securely in Supabase (NOT in your code).

```powershell
supabase secrets set PERPLEXITY_API_KEY=your_actual_api_key_here
```

**Example:**
```powershell
supabase secrets set PERPLEXITY_API_KEY=pplx-abc123def456...
```

**Verify the secret was set:**

```powershell
supabase secrets list
```

You should see `PERPLEXITY_API_KEY` in the list.

> **Important:** Never commit API keys to Git! The Edge Functions will access this via `Deno.env.get('PERPLEXITY_API_KEY')`.

---

## Step 5: Deploy Edge Functions

Deploy all three Edge Functions:

```powershell
# Deploy main query processor
supabase functions deploy process-query

# Deploy bus tracker
supabase functions deploy track-bus

# Deploy nearby stops
supabase functions deploy get-nearby-stops
```

Each deployment will:
1. Upload the function code
2. Install dependencies
3. Provide you with a URL

**Expected output:**
```
Deploying function: process-query
Version: xxx
Deployed successfully!
Function URL:
  https://YOUR_PROJECT_REF.supabase.co/functions/v1/process-query
```

---

## Step 6: Get Your Supabase Credentials

You need two keys for your Android app:

### 6.1 Supabase URL

```powershell
# This is shown after deployment, or get it from dashboard
https://YOUR_PROJECT_REF.supabase.co
```

### 6.2 Anon Key (Public API Key)

1. Go to https://supabase.com/dashboard
2. Open your project
3. Go to Settings → API
4. Copy the `anon` / `public` key (NOT the service_role key)

### 6.3 Update Android App

Update your `local.properties` file:

```properties
# D:\SaharaUser\local.properties

# Supabase
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.anon.key=YOUR_ANON_KEY_HERE

# Perplexity (if using client-side, otherwise skip)
perplexity.api.key=YOUR_PERPLEXITY_KEY
```

---

## Step 7: Test the Deployment

### Test with cURL

```powershell
# Test process-query
curl -X POST `
  https://YOUR_PROJECT_REF.supabase.co/functions/v1/process-query `
  -H "Authorization: Bearer YOUR_ANON_KEY" `
  -H "Content-Type: application/json" `
  -d '{\"userId\":\"test_user\",\"query\":\"How do I get from MG Road to Electronic City?\"}'
```

Replace:
- `YOUR_PROJECT_REF` with your actual project reference
- `YOUR_ANON_KEY` with your anon key from Step 6.2

**Expected response:**
```json
{
  "success": true,
  "response": "🚌 Bus 335E • 15 km • ~45 min",
  "intent": "FIND_ROUTE",
  "executionTimeMs": 1234
}
```

### Test track-bus

```powershell
curl -X POST `
  https://YOUR_PROJECT_REF.supabase.co/functions/v1/track-bus `
  -H "Authorization: Bearer YOUR_ANON_KEY" `
  -H "Content-Type: application/json" `
  -d '{\"busName\":\"335E\"}'
```

### Test get-nearby-stops

```powershell
curl -X POST `
  https://YOUR_PROJECT_REF.supabase.co/functions/v1/get-nearby-stops `
  -H "Authorization: Bearer YOUR_ANON_KEY" `
  -H "Content-Type: application/json" `
  -d '{\"latitude\":12.9758,\"longitude\":77.6066,\"radiusKm\":1.0}'
```

---

## Step 8: Monitor Functions

### View Logs

```powershell
# View logs for process-query
supabase functions logs process-query

# Follow logs in real-time
supabase functions logs process-query --follow
```

### Check Function Status

```powershell
supabase functions list
```

---

## Troubleshooting

### Error: "Failed to deploy"

**Solution:** Make sure you're in the right directory:
```powershell
cd D:\SaharaUser
```

### Error: "Secret not found"

**Solution:** Re-set the secret:
```powershell
supabase secrets set PERPLEXITY_API_KEY=your_key
```

### Error: "Authorization failed"

**Solution:** 
1. Check you're using the `anon` key (NOT `service_role`)
2. Make sure the key is in the Authorization header

### Error: "Cannot find module"

**Solution:** The Edge Functions use Deno imports. Make sure you deployed with:
```powershell
supabase functions deploy process-query --no-verify-jwt
```

### Function times out

**Solution:** Edge Functions have a 60s timeout. If processing takes longer:
1. Check your Supabase database has indexed tables
2. Reduce the number of records being processed
3. Add pagination to large queries

---

## Updating Functions

When you make changes to the code:

```powershell
# Re-deploy the function
supabase functions deploy process-query

# No need to re-set secrets - they persist
```

---

## Cost Information

Supabase Edge Functions pricing:
- **Free tier**: 500,000 invocations/month
- **Pro tier**: $25/month for 2M invocations
- After that: $2 per 1M invocations

Check your usage:
1. Go to https://supabase.com/dashboard
2. Your Project → Settings → Billing

---

## Next Steps

1. ✅ Deploy functions to Supabase
2. ✅ Test with cURL
3. 🔄 Update Android app to use `AgentRepository`
4. 🔄 Test with Android app
5. 🔄 Monitor logs for any errors
6. 🔄 (Optional) Set up database indexes for faster queries

---

## Quick Reference Commands

```powershell
# Login
supabase login

# Link project
supabase link --project-ref YOUR_REF

# Set secret
supabase secrets set PERPLEXITY_API_KEY=your_key

# Deploy all functions
supabase functions deploy process-query
supabase functions deploy track-bus
supabase functions deploy get-nearby-stops

# View logs
supabase functions logs process-query --follow

# List functions
supabase functions list

# List secrets
supabase secrets list
```

---

## Support

If you encounter issues:
1. Check Supabase docs: https://supabase.com/docs/guides/functions
2. Check logs: `supabase functions logs process-query`
3. Verify your database has data in `vehicles`, `route_stops`, `locations` tables
