Folder Viewer — পরবর্তী ধাপ: Server Request + Phone Approval

এই ZIP-এ Android source, Edge Function এবং SQL আছে। APK এখানে build করা হয়নি; GitHub Actions দিয়ে build করতে হবে।

নতুন কাজ:
- অ্যাপে “সার্ভারের অনুরোধ দেখুন” বাটন।
- ফোনে ম্যানুয়ালি চাপলে pending server request আনা হবে।
- list_files অনুরোধে ফোনে অনুমতি চাইবে; অনুমতি দিলে ফাইলের নাম/পাথ/সাইজ সার্ভারে পাঠাবে।
- delete_file অনুরোধে ফোনে ফাইলের পাথ দেখিয়ে নিশ্চিতকরণ চাইবে; অনুমতি না দিলে মুছবে না।
- কেবল shared storage-এর ভেতরের সাধারণ ফাইল ডিলিট করা যায়; ফোল্ডার/recursive delete নেই।
- স্বয়ংক্রিয় ব্যাকগ্রাউন্ড polling নেই; ব্যবহারকারী বাটন চাপলেই অনুরোধ দেখা হয়।

প্রথমে Supabase:
1. Dashboard > SQL Editor খুলুন।
2. supabase/device_requests.sql-এর পুরো SQL পেস্ট করে Run করুন।
3. Edge Functions > device-gateway খুলুন।
4. index.ts-এ থাকা কোড পুরো Replace করে Deploy করুন।
5. DEVICE_ENROLLMENT_CODE secret আগের মতো থাকবে; এটি app source-এ দেবেন না।

Request তৈরি করার নিয়ম (শুরুতে Dashboard থেকে):
Supabase Table Editor > device_requests > Insert row:
- device_id: ফোনের device ID (যেমন 2)
- action: list_files অথবা delete_file
- status: pending
- request_payload: JSON, যেমন {"path":"/storage/emulated/0/Download"} অথবা {"path":"/storage/emulated/0/Download/test.txt"}
- expires_at ফাঁকা রাখলে default 24 ঘণ্টা।

গুরুত্বপূর্ণ:
- delete_file-এর path সঠিক ও নির্দিষ্ট ফাইলের path হতে হবে।
- ফোনের ব্যবহারকারী প্রতিটি অনুরোধ অনুমোদন/বাতিল করবেন। Server request এলেই স্বয়ংক্রিয় delete হয় না।
- এই পর্যায়ে public web panel নেই; Table Editor ব্যবহার করা হচ্ছে। Web panel যোগ করার আগে Supabase Auth দিয়ে admin access সুরক্ষিত করা দরকার। Publishable key client-এ থাকতে পারে, কিন্তু sb_secret/service-role key কখনো app, browser HTML, GitHub repo বা screenshot-এ দেবেন না।
- Existing device ID ও token থাকলে অ্যাপটি একই নিবন্ধন ব্যবহার করবে; আবার register করতে হবে না।
