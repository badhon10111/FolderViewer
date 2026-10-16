Folder Viewer — Background Location Request (best-effort)

এই আপডেটে:
- Ping সফল হলে এবং প্রায় প্রতি ২ মিনিটের চক্রে সার্ভারের pending request poll করা হয়।
- ওয়েব প্যানেল থেকে স্পষ্টভাবে get_location request এলে, Location permission ও device Location ON থাকলে একবারের location server-এ পাঠানোর চেষ্টা হয়।
- check_location_status request এলে permission, Location ON/OFF এবং background permission-এর অবস্থা ফেরত পাঠানো হয়।
- list_files/delete_file background-এ auto-execute করা হয় না; এগুলোর user approval flow আগের মতো আলাদা থাকে।

গুরুত্বপূর্ণ Android সেটআপ:
1. App খুলে Location permission দিন।
2. Android 10+ এ Settings > Apps > Folder Viewer > Permissions > Location > Allow all the time নির্বাচন করুন (যদি ফোনে এই অপশন থাকে)। শুধু manifest permission যোগ করলেই runtime permission পাওয়া যায় না।
3. ফোনের Location/GPS ON রাখুন।
4. Battery settings-এ Folder Viewer-এর background restriction বন্ধ/Unrestricted করতে হতে পারে। কিছু OEM Android প্রতি ২ মিনিটে চলা নিশ্চিত করে না।
5. Location OFF বা permission না থাকলে app নিজে থেকে সেটিংস ON করবে না; request-এ error result পাঠাবে। Background থেকে system prompt জোর করে খোলা Android অনুমতি দেয় না।

Database:
`check_location_status` request action ব্যবহার করলে device_requests-এর action CHECK constraint-এ এটি অনুমোদিত থাকতে হবে। Supabase SQL Editor-এ constraint-এর নাম আগে দেখে তারপর সেই constraint update করুন; বিদ্যমান `list_files`, `delete_file`, `get_location` action যেন বাদ না যায়। উদাহরণ (constraint-এর নাম ভিন্ন হলে নাম বদলাতে হবে):

ALTER TABLE public.device_requests DROP CONSTRAINT IF EXISTS device_requests_action_check;
ALTER TABLE public.device_requests ADD CONSTRAINT device_requests_action_check
CHECK (action IN ('list_files', 'delete_file', 'get_location', 'check_location_status'));

Deployment:
- `supabase/functions/device-gateway/index.ts` deploy করুন Supabase-এর `device-gateway` function-এ।
- App source GitHub-এ push করে Actions থেকে APK build করুন।

এই ZIP-এর Android build এখানে চালিয়ে যাচাই করা হয়নি। প্রথমে test device-এ test করুন।
