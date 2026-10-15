FolderViewer: Request-on-demand location (source update)

Included files:
- app/src/main/AndroidManifest.xml: coarse/fine location permissions.
- app/src/main/java/com/example/folderviewer/MainActivity.java: handles "get_location" server requests.
- FolderViewer-Web/index.html: adds "লোকেশন আনুন" button per device and displays latest returned coordinates.
- admin-gateway/index.ts: adds authenticated request_location action and returns latest location request status/results.

Important behavior:
- Location is NOT continuously collected or uploaded.
- The web panel creates a request that expires in 10 minutes.
- The phone must be online, Folder Viewer must be open, and the user must tap "সার্ভারের অনুরোধ দেখুন" to poll the request.
- The app shows a confirmation dialog. The user can deny it.
- Android runtime location permission is requested only after the user approves the location request in the app.
- If allowed, the app makes a one-time request to Android LocationManager (GPS/network providers) and waits up to 30 seconds.
- The coordinates are uploaded only as the response to that specific request. If permission/location service is unavailable, or no fix is obtained, an error status is sent instead.
- No background location service is added; no location is sent when there is no pending request.
- "Last location" remains visible in the web panel after a request completes. It is not a live stream and can become stale.

Deployment steps:
1. Keep backups of your currently working files.
2. Deploy admin-gateway/index.ts to the existing Supabase Edge Function named admin-gateway.
3. Replace FolderViewer-Web repository's index.html with FolderViewer-Web/index.html from this ZIP, then commit.
4. Replace Android source files with app/ files from this ZIP and build/install a new APK using your existing GitHub Actions workflow.
5. Confirm the existing device-gateway function still supports poll_requests and complete_request actions (the uploaded source did).
6. On phone: open Folder Viewer, tap "সার্ভারের অনুরোধ দেখুন", accept the in-app request, then accept Android's location permission if asked.
7. In the web panel, press Refresh to view the returned coordinates and "মানচিত্রে দেখুন".

Security:
- Keep Supabase secret/service-role keys server-side only. Do not put them in the Android app or web page.
- The admin-gateway still checks the configured ADMIN_UID.
- The app does not act on location requests silently; it requires in-app approval and Android permission.
