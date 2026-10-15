Folder Viewer — Presence + Location toggle status fixes

এই আপডেটে:
1. PingService-এর ২ মিনিটের Ping timer network capability callback দিয়ে বারবার reset হওয়া আটকানো হয়েছে। সফল Ping-এর পর পরবর্তী Ping ২ মিনিট পরে যাবে।
2. Online status ৪ মিনিটের tolerance ব্যবহার করে, যাতে Android scheduling/network delay-তে ভুল Offline না দেখায়।
3. Web panel-এর Ping Test ২ মিনিটে উত্তর না পেলে failed/timeout হবে এবং “Offline বা service বন্ধ” দেখাবে; pending text-এ অনির্দিষ্টকাল আটকে থাকবে না।
4. “ফোনের Location ON/OFF চেক” বোতাম যোগ হয়েছে। ফোনের background service Location setting পড়ে completed response ফেরত দেবে; GPS coordinates সংগ্রহ করে না।
5. device_requests_action_check-এ check_location_status অনুমোদন যোগ করতে হবে (SETUP_Ping_Test_BN.txt-এর SQL একবার চালাতে হবে)।

Deploy order:
- Supabase SQL Editor-এ SETUP_Ping_Test_BN.txt-এর SQL চালাও।
- admin-gateway/index.ts deploy করো।
- FolderViewer-Web/index.html GitHub Pages repo-তে replace করো।
- Android source files update করে নতুন APK build/install করো।

এখানে APK build বা real-device end-to-end test করা হয়নি।
