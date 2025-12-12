# Deploy Edge Functions via Supabase Dashboard

## Complete Web-Based Deployment Guide

No CLI needed! Deploy everything through your browser.

---

## Step 1: Login to Supabase Dashboard

1. Go to: **https://supabase.com/dashboard**
2. Sign in with your account
3. Select your project (or create a new one if needed)

---

## Step 2: Set API Key Secret

### 2.1 Navigate to Secrets

1. In your project dashboard, click **Settings** (gear icon in left sidebar)
2. Click **Edge Functions**
3. Scroll to **Secrets** section

### 2.2 Add Perplexity API Key

1. Click **Add Secret**
2. Name: `PERPLEXITY_API_KEY`
3. Value: `your_perplexity_api_key_here` (get from https://www.perplexity.ai/settings/api)
4. Click **Save**

> ✅ Your API key is now securely stored!

---

## Step 3: Create Edge Functions

You'll create 3 functions. For each one:

### 3.1 Create Function 1: process-query

1. Click **Edge Functions** in the left sidebar
2. Click **Create a new function**
3. Function name: `process-query`
4. Click **Create function**

5. **Delete the default code** completely
6. **Copy and paste** the following code:

```typescript
import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

// [COPY THE ENTIRE CONTENT OF: D:\SaharaUser\supabase\functions\process-query\index.ts]
```

> 📋 **Where to get the code:** Open `D:\SaharaUser\supabase\functions\process-query\index.ts` and copy ALL the content

7. Click **Deploy** (top right)
8. Wait for deployment to complete (~30 seconds)

### 3.2 Create Shared Files

Before creating the other functions, you need to upload the shared files:

#### Upload _shared/types.ts

1. Click **Create a new function**
2. Function name: `_shared/types`
3. **IMPORTANT:** This creates a shared module, not a function
4. Copy content from: `D:\SaharaUser\supabase\functions\_shared\types.ts`
5. Click **Save** (don't deploy, just save)

#### Repeat for other shared files:

- `_shared/algorithms` → Copy from `algorithms.ts`
- `_shared/query-parser` → Copy from `query-parser.ts`
- `_shared/orchestrator` → Copy from `orchestrator.ts`
- `_shared/agents` → Copy from `agents.ts`

> 💡 **Alternative:** You may need to inline these imports into each function if the dashboard doesn't support `_shared` folder structure properly.

### 3.3 Create Function 2: track-bus

1. Click **Create a new function**
2. Function name: `track-bus`
3. Copy content from: `D:\SaharaUser\supabase\functions\track-bus\index.ts`
4. Click **Deploy**

### 3.4 Create Function 3: get-nearby-stops

1. Click **Create a new function**
2. Function name: `get-nearby-stops`
3. Copy content from: `D:\SaharaUser\supabase\functions\get-nearby-stops\index.ts`
4. Click **Deploy**

---

## Step 4: Get Your Credentials

### 4.1 Get Supabase URL

1. Go to **Settings** → **API**
2. Copy **Project URL**
   - Example: `https://abcdefghijk.supabase.co`

### 4.2 Get Anon Key

1. Still in **Settings** → **API**
2. Under **Project API keys**, copy the `anon` / `public` key
   - This is the one labeled "anon"
   - NOT the service_role key!

### 4.3 Update Android App

Open `D:\SaharaUser\local.properties` and add:

```properties
supabase.url=https://your-project-ref.supabase.co
supabase.anon.key=your_anon_key_here
```

---

## Step 5: Test Your Functions

### Test in Supabase Dashboard

1. Go to **Edge Functions**
2. Click on `process-query`
3. Click **Invoke** tab
4. Change method to **POST**
5. Add request body:

```json
{
  "userId": "test_user",
  "query": "How do I get from MG Road to Electronic City?"
}
```

6. Click **Send request**

**Expected response:**

```json
{
  "success": true,
  "response": "🚌 Bus 335E • 15 km • ~45 min",
  "intent": "FIND_ROUTE",
  "executionTimeMs": 1234
}
```

### Test with Browser (Alternative)

Open your browser console and run:

```javascript
fetch('https://YOUR_PROJECT_REF.supabase.co/functions/v1/process-query', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer YOUR_ANON_KEY',
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    userId: 'test',
    query: 'How do I get from MG Road to Electronic City?'
  })
})
.then(r => r.json())
.then(console.log);
```

---

## Step 6: View Logs

1. Go to **Edge Functions**
2. Click on your function (e.g., `process-query`)
3. Click **Logs** tab
4. See real-time execution logs

---

## Important Notes

### If `_shared` imports don't work:

The Supabase dashboard might not support the `_shared` folder structure. In that case:

**Solution:** Combine all code into single files

For `process-query/index.ts`:
1. Copy content from `types.ts` and paste at the top
2. Copy content from `algorithms.ts` below that
3. Copy content from `query-parser.ts` below that
4. Copy content from `orchestrator.ts` below that
5. Copy content from `agents.ts` below that
6. Finally, the main process-query code at the bottom
7. Remove all `import` statements that reference `_shared`

This creates one large file but will work in the dashboard.

---

## Complete Checklist

- [ ] Login to Supabase Dashboard
- [ ] Add `PERPLEXITY_API_KEY` secret
- [ ] Create `process-query` function
- [ ] Create `track-bus` function
- [ ] Create `get-nearby-stops` function
- [ ] Get Supabase URL
- [ ] Get Anon Key
- [ ] Update `local.properties`
- [ ] Test functions in dashboard
- [ ] Check logs for errors

---

## Troubleshooting

### Error: "Module not found" for _shared imports

**Solution:** Inline all shared code into each function file (see note above)

### Error: "Secret not found"

**Solution:** Make sure secret name is exactly `PERPLEXITY_API_KEY` (case-sensitive)

### Error: "CORS error"

**Solution:** This is expected from browser. Use the Supabase dashboard's Invoke tab instead.

### Function times out

**Solution:** 
- Check your database has data in `vehicles`, `route_stops`, `locations` tables
- Add indexes to these tables for faster queries

---

## Next Steps

Once all functions are deployed:

1. ✅ Test each function in the dashboard
2. ✅ Update your Android app's `local.properties`
3. ✅ Build and run your Android app
4. ✅ Test the full flow end-to-end

Your multi-agent system is now live! 🚀
