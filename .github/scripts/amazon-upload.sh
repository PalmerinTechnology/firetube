#!/usr/bin/env bash
# Uploads an APK to the Amazon Appstore listing and submits it for review, using Amazon's
# App Submission API (https://developer.amazon.com/docs/app-submission-api/overview.html).
#
# Usage: amazon-upload.sh <apk>
# Env:   AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET  (Security Profile with App Submission API access)
#        AMAZON_APP_ID                           (amzn1.devportal.mobileapp.…)
#        AMAZON_SUBMIT=false                     to upload into a draft edit without submitting
set -euo pipefail

APK="$1"
API="https://developer.amazon.com/api/appstore/v1/applications/${AMAZON_APP_ID}"

for v in AMAZON_CLIENT_ID AMAZON_CLIENT_SECRET AMAZON_APP_ID; do
  [ -n "${!v:-}" ] || { echo "::error::$v isn't set"; exit 1; }
done

# 1. Access token (client credentials)
TOKEN=$(curl -sf https://api.amazon.com/auth/o2/token \
  -d grant_type=client_credentials \
  -d client_id="$AMAZON_CLIENT_ID" \
  -d client_secret="$AMAZON_CLIENT_SECRET" \
  -d scope=appstore::apps:readwrite | jq -r .access_token)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || { echo "::error::Couldn't get an Amazon access token"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

# 2. Reuse an open edit, or start one
EDIT=$(curl -sf "${AUTH[@]}" "$API/edits" | jq -r '.id // empty')
if [ -z "$EDIT" ]; then
  EDIT=$(curl -sf -X POST "${AUTH[@]}" "$API/edits" | jq -r .id)
fi
echo "Edit: $EDIT"

# 3. Replace the listing's APK (keeps its device targeting); upload a new one if there's none
APK_ID=$(curl -sf "${AUTH[@]}" "$API/edits/$EDIT/apks" | jq -r '.[0].id // empty')
if [ -n "$APK_ID" ]; then
  ETAG=$(curl -sfI "${AUTH[@]}" "$API/edits/$EDIT/apks/$APK_ID" | tr -d '\r' | awk -F': ' 'tolower($1)=="etag"{print $2}')
  curl -sf -X PUT "${AUTH[@]}" -H "If-Match: $ETAG" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" "$API/edits/$EDIT/apks/$APK_ID/replace" > /dev/null
  echo "Replaced APK $APK_ID"
else
  curl -sf -X POST "${AUTH[@]}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" "$API/edits/$EDIT/apks/upload" > /dev/null
  echo "Uploaded APK"
fi

# 4. Submit for review (or leave the draft for a manual check)
if [ "${AMAZON_SUBMIT:-true}" = "true" ]; then
  ETAG=$(curl -sfI "${AUTH[@]}" "$API/edits/$EDIT" | tr -d '\r' | awk -F': ' 'tolower($1)=="etag"{print $2}')
  curl -sf -X POST "${AUTH[@]}" -H "If-Match: $ETAG" "$API/edits/$EDIT/commit" > /dev/null
  echo "Submitted edit $EDIT for review"
else
  echo "Left edit $EDIT as a draft (AMAZON_SUBMIT=false)"
fi
