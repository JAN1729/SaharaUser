@echo off
REM ========================================
REM Supabase Edge Functions Deployment Script
REM ========================================

echo.
echo ========================================
echo  SUPABASE DEPLOYMENT SCRIPT
echo ========================================
echo.

REM Change to project directory
cd /d D:\SaharaUser

echo Step 1: Checking Supabase CLI...
echo.

REM Try to use supabase CLI
where supabase >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] Supabase CLI not found globally.
    echo Installing via Scoop is recommended.
    echo.
    echo Please install Scoop first:
    echo   https://scoop.sh/
    echo.
    echo Then run:
    echo   scoop bucket add supabase https://github.com/supabase/scoop-bucket.git
    echo   scoop install supabase
    echo.
    pause
    exit /b 1
)

echo [OK] Supabase CLI found
echo.

REM ========================================
REM Step 2: Login (requires browser)
REM ========================================
echo Step 2: Logging in to Supabase...
echo This will open your browser for authentication.
echo.
pause

supabase login

if %errorlevel% neq 0 (
    echo [ERROR] Login failed
    pause
    exit /b 1
)

echo [OK] Login successful
echo.

REM ========================================
REM Step 3: Link to project
REM ========================================
echo Step 3: Linking to Supabase project...
echo.
echo Please enter your Supabase Project Reference ID
echo (Found in: Supabase Dashboard -^> Settings -^> General)
echo.
set /p PROJECT_REF="Project Reference ID: "

if "%PROJECT_REF%"=="" (
    echo [ERROR] Project reference ID is required
    pause
    exit /b 1
)

echo.
echo Linking to project: %PROJECT_REF%
echo.

supabase link --project-ref %PROJECT_REF%

if %errorlevel% neq 0 (
    echo [ERROR] Failed to link project
    pause
    exit /b 1
)

echo [OK] Project linked successfully
echo.

REM ========================================
REM Step 4: Set API Key Secret
REM ========================================
echo Step 4: Setting Perplexity API Key...
echo.
echo Please enter your Perplexity API Key
echo (Get it from: https://www.perplexity.ai/settings/api)
echo.
set /p API_KEY="Perplexity API Key: "

if "%API_KEY%"=="" (
    echo [ERROR] API key is required
    pause
    exit /b 1
)

echo.
echo Setting secret...
echo.

supabase secrets set PERPLEXITY_API_KEY=%API_KEY%

if %errorlevel% neq 0 (
    echo [ERROR] Failed to set secret
    pause
    exit /b 1
)

echo [OK] API key set successfully
echo.

REM ========================================
REM Step 5: Deploy Functions
REM ========================================
echo Step 5: Deploying Edge Functions...
echo.

echo Deploying process-query...
supabase functions deploy process-query --no-verify-jwt

if %errorlevel% neq 0 (
    echo [ERROR] Failed to deploy process-query
    pause
    exit /b 1
)

echo [OK] process-query deployed
echo.

echo Deploying track-bus...
supabase functions deploy track-bus --no-verify-jwt

if %errorlevel% neq 0 (
    echo [ERROR] Failed to deploy track-bus
    pause
    exit /b 1
)

echo [OK] track-bus deployed
echo.

echo Deploying get-nearby-stops...
supabase functions deploy get-nearby-stops --no-verify-jwt

if %errorlevel% neq 0 (
    echo [ERROR] Failed to deploy get-nearby-stops
    pause
    exit /b 1
)

echo [OK] get-nearby-stops deployed
echo.

REM ========================================
REM Step 6: Summary
REM ========================================
echo.
echo ========================================
echo  DEPLOYMENT COMPLETE!
echo ========================================
echo.
echo Your Edge Functions are now live at:
echo   https://%PROJECT_REF%.supabase.co/functions/v1/process-query
echo   https://%PROJECT_REF%.supabase.co/functions/v1/track-bus
echo   https://%PROJECT_REF%.supabase.co/functions/v1/get-nearby-stops
echo.
echo Next steps:
echo   1. Get your Anon Key from Supabase Dashboard -^> Settings -^> API
echo   2. Update local.properties with supabase.url and supabase.anon.key
echo   3. Test the functions with your Android app
echo.
echo To view logs:
echo   supabase functions logs process-query --follow
echo.
pause
