package com.jarvis.ai.tools

import org.json.JSONArray
import org.json.JSONObject

/** Gemini function declarations (shared by chat mode and Live voice mode). */
object ToolDefs {
    private fun fn(name: String, desc: String, vararg params: Triple<String, String, String>, required: List<String> = emptyList()): JSONObject {
        val props = JSONObject()
        params.forEach { (n, type, d) -> props.put(n, JSONObject().put("type", type).put("description", d)) }
        val o = JSONObject().put("name", name).put("description", desc)
        if (params.isNotEmpty()) o.put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        return o
    }
    private fun s(n: String, d: String) = Triple(n, "string", d)
    private fun i(n: String, d: String) = Triple(n, "integer", d)
    private fun b(n: String, d: String) = Triple(n, "boolean", d)

    val declarations: JSONArray by lazy {
        JSONArray(listOf(
            // apps & screen
            fn("open_app", "Open any installed app by name or package (WhatsApp, YouTube, Facebook, Instagram, TikTok, ChatGPT, Gemini, Claude, PUBG, Free Fire, Settings, Camera, Gallery...).", s("name", "App name or package"), required = listOf("name")),
            fn("list_apps", "List all installed apps (label and package name).", s("filter", "Optional text filter")),
            fn("install_app", "Install an app by touching the screen like a human: opens the Play Store page (or an .apk file) and taps Install itself. Needs Accessibility ON. There is no silent-install API for a normal app — Google does not allow it.", s("app", "App name, package id (com.whatsapp) or .apk filename"), required = listOf("app")),
            fn("whatsapp_web", "Open WhatsApp Web in the in-app browser so you can link the phone by scanning the QR code once (Settings > Linked devices in WhatsApp). After linking, Jarvis can read and send WhatsApp messages through the browser. There is no official WhatsApp API for third-party apps."),
            fn("identify_object", "Look at the current screen and identify the objects / UI elements / people / animals / text on it, with their positions. Uses Gemini Vision — needs a Gemini API key."),
            fn("recommend", "Suggest music, videos and activities that fit the user's current mood (happy, sad, tired, stressed, bored, angry, calm). Uses the detected mood when no mood is given.", s("mood", "Mood: happy, sad, tired, stressed, bored, angry, calm")),
            fn("find_phone", "Make the phone ring loudly (alarm + vibration + torch flashing) so you can find it. Say 'stop' to stop it.", i("seconds", "How many seconds to ring (default 30, max 120)")),
            fn("stop_ringing", "Stop the phone finder ringing."),
            fn("read_screen", "See the current screen: returns visible texts/buttons/inputs with tap coordinates, and attaches a screenshot image.", b("with_image", "Attach screenshot (default true)")),
            fn("tap_text", "Tap a button/element whose visible text, description or id contains this text.", s("text", "Text on the element"), b("long_press", "Long press instead"), required = listOf("text")),
            fn("tap", "Tap screen coordinates in pixels.", i("x", "x"), i("y", "y"), b("long_press", "Long press"), required = listOf("x", "y")),
            fn("swipe", "Swipe from (x1,y1) to (x2,y2).", i("x1", ""), i("y1", ""), i("x2", ""), i("y2", ""), i("duration_ms", "default 350"), required = listOf("x1", "y1", "x2", "y2")),
            fn("scroll", "Scroll the screen. down = next content (next reel/short/post), up = previous, left/right.", s("direction", "up|down|left|right"), i("times", "How many times (default 1)"), required = listOf("direction")),
            fn("type_text", "Type text into the focused (or first) input field.", s("text", "Text"), b("append", "Append instead of replace"), b("press_enter", "Press enter/search after typing"), required = listOf("text")),
            fn("press_key", "System navigation / action: back, home, recents, notifications, quick_settings, power_menu, lock (screen off), screenshot, split_screen, send (taps chat Send button).", s("key", "Key name"), required = listOf("key")),
            fn("wait", "Wait for the screen to load.", i("ms", "Milliseconds (max 8000)"), required = listOf("ms")),
            // communication
            fn("make_call", "Place a phone call to a number or contact name.", s("to", "Phone number or contact name"), b("speaker", "Turn on speaker"), required = listOf("to")),
            fn("answer_call", "Answer the currently ringing call.", b("ai_talk", "If true, turn on speaker and let the assistant talk to the caller")),
            fn("answer_whatsapp_call", "Answer the currently ringing WhatsApp call: taps the WhatsApp Answer button, turns the speaker on, and greets the caller. Honest limit: Jarvis cannot join the call as a real voice participant — Android never lets an app inject audio into WhatsApp, so after the greeting the owner must take the phone. If Away Mode is ON, Jarvis instead speaks the away message and ends the call."),
            fn("set_away_mode", "Turn Away Mode on or off. When ON, Jarvis answers WhatsApp calls itself, speaks your away message, and ends the call (answering machine). When OFF, it only greets and waits for the owner.", b("enabled", "true = Away Mode ON, false = OFF")),
            fn("end_call", "Hang up / reject the current call."),
            // v1.3.2: multi-voice + deploy
            fn("change_voice", "Change Jarvis's speaking voice (multi-voice). Works in background live voice too. Pick one of: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr, Callirrhoe, Autonoe, Erinome, Sulafat.", s("voice", "Voice name"), required = listOf("voice")),
            fn("deploy_website", "Deploy a website file from the workspace to GitHub Pages (hosting). Default file is index.html. Needs GitHub connected (Settings > Connect > Authorize GitHub). Returns the live Pages URL.", s("file", "Workspace file to deploy (default index.html)")),
            fn("create_repo", "Create a NEW GitHub repository (public or private). Needs GitHub connected (token in Settings > Connect). The new repo becomes the active repo for later pushes.", s("name", "Repository name, e.g. my-website"), b("private", "true = private repo (default false)"), s("description", "Short description"), required = listOf("name")),
            fn("push_zip_to_github", "Unzip a ZIP file on the phone and push every text file inside to the GitHub repo (creates/updates files). Binary files and files over 1MB are skipped. The ZIP is also extracted on the phone under workspace/unzipped/.", s("file", "ZIP filename, e.g. my-site.zip"), s("folder", "Optional folder inside the repo"), required = listOf("file")),
            fn("supabase_status", "Check whether Supabase cloud sync is configured and its last sync time. Supabase is optional — Jarvis works fully offline without it."),
            fn("supabase_sync", "Sync memory, history and settings to/from the optional Supabase cloud (PostgREST table jarvis_sync). Works fully offline without it — Jarvis stays local-first.", s("direction", "'up' (phone -> cloud) or 'down' (cloud -> phone)"), required = listOf("direction")),
            // v1.3.4: Render deploy
            fn("render_deploy", "Trigger a manual deploy on Render now. Needs the Render API key + Service ID, or a Deploy Hook URL, in Settings > Hosting / Render. Returns the deploy id and dashboard link.", s("service", "Optional: service name to mention (the deploy uses the Service ID from Settings)")),
            fn("render_status", "Check the latest deploy status of your Render service (live / building / failed) and its live URL. Needs the Render API key + Service ID in Settings > Hosting / Render."),
            // v1.3.6: video making, APK building, media editing, shopping
            fn("make_video", "Make a video by yourself from photos — a slideshow MP4 (720x1280) saved to Gallery > Movies > JarvisAI. Give workspace photo filenames, or leave empty to use the newest gallery photos.", s("images", "Comma-separated workspace photo filenames (optional — newest gallery photos if empty)"), i("seconds", "Seconds per photo (default 3)"), s("text", "Optional text shown on the video")),
            fn("build_apk", "Build an APK with Gradle + Kotlin: pushes a GitHub Actions workflow to your GitHub repo and builds it on GitHub's free servers, then downloads the finished APK to Downloads > JarvisAI. Needs GitHub connected (token + owner/repo in Settings) and the repo to be an Android Gradle project. The build takes 2-5 minutes."),
            fn("check_build", "Check the latest APK build status; downloads the APK to Downloads > JarvisAI when it finished successfully."),
            fn("edit_video", "Edit a video in CapCut (or YouCut if CapCut is not installed) — opens the video in the editor app. Professional editing taps are done by the user in the app.", i("index", "0 = newest gallery video"), s("file", "Optional workspace video filename")),
            fn("edit_photo", "Edit a photo in the photo editor (Snapseed if installed, else the system editor).", i("index", "0 = newest gallery photo"), s("file", "Optional workspace photo filename")),
            fn("remove_background", "Remove the background of a photo (professional cutout, transparent PNG, saved to Gallery > Pictures > JarvisAI). Needs a remove.bg API key in Settings > Photo / Video / Build; without a key it opens remove.bg in the browser instead.", i("index", "0 = newest gallery photo"), s("file", "Optional workspace photo filename")),
            fn("buy_online", "Order online on Flipkart or Amazon: opens the product search in the browser, you add to cart and go to checkout. NEVER pay without the user's explicit confirmation — prepare everything and stop at payment; OTP/UPI is the user's.", s("site", "flipkart or amazon"), s("product", "What to buy"), required = listOf("site", "product")),
            fn("free_domain", "Get a free domain for a website: opens the free-domain options in the browser (is-a.dev free subdomain, or the free github.io / onrender.com / netlify.app address your published site already has)."),
            // v1.3.7: website login / account creation
            fn("login_website", "Log in to any website, or create a new account on it. Opens the site's login/signup page in the browser; then use read_screen + type_text + tap_text to fill the form with the email/password the user gives you. If the site asks for OTP/2FA, ask the user for the code. Never store passwords.", s("site", "Website name or URL (google, facebook, instagram, github, amazon, flipkart, x, netflix, render, netlify...)"), s("action", "login or signup (default login)")),
            // v1.3.8: smart home + self feature requests
            fn("control_device", "Control a smart home device (light, fan, AC, switch, plug) — turn it on/off. Needs Home Assistant URL + token in Settings > Smart Home. Without Home Assistant, opens the Google Home app instead.", s("entity", "Device entity id, e.g. light.bedroom, fan.living_room, switch.tv"), s("action", "on, off, or toggle"), required = listOf("entity", "action")),
            fn("device_status", "List your smart home devices and their states (on/off). Needs Home Assistant URL + token in Settings > Smart Home."),
            fn("request_feature", "Ask Jarvis to add a new feature to itself: saves the request and pushes it to GitHub. Jarvis cannot change its own running code — the new feature comes as a new version: code is written, pushed to GitHub, build_apk builds it, and the user installs the update.", s("feature", "What feature to add"), required = listOf("feature")),
            fn("send_whatsapp", "Send a WhatsApp message to a contact name or number (with country code).", s("to", "Contact name or number"), s("message", "Message text"), b("business", "Use WhatsApp Business"), required = listOf("to", "message")),
            fn("send_sms", "Send an SMS.", s("to", "Contact name or number"), s("message", "Text"), required = listOf("to", "message")),
            fn("reply_notification", "Reply to a recent chat notification (WhatsApp, Messenger, Telegram, Instagram, SMS) using its quick-reply.", s("from", "Sender name or app"), s("message", "Reply text"), required = listOf("from", "message")),
            fn("read_notifications", "Read recent notifications / incoming messages.", i("limit", "default 15")),
            fn("find_contact", "Search contacts by name and return numbers.", s("name", "Name"), required = listOf("name")),
            // media & social
            fn("play_youtube", "Search and play a video on YouTube.", s("query", "What to play"), required = listOf("query")),
            fn("open_url", "Open a website or deep link in the browser/app.", s("url", "URL"), required = listOf("url")),
            fn("web_search", "Google search in the browser.", s("query", "Query"), required = listOf("query")),
            fn("share_media_to_app", "Share/upload the latest (or nth latest) photo or video from the gallery into an app (YouTube, Facebook, Instagram, TikTok, WhatsApp). After calling, use read_screen and taps to finish posting.", s("app", "App name"), s("media_type", "video|image"), i("index", "0 = newest"), s("caption", "Optional caption text"), required = listOf("app")),
            fn("list_gallery", "List recent photos/videos in the gallery.", s("media_type", "video|image"), i("limit", "default 10")),
            fn("open_camera", "Open the camera app.", b("video", "Video mode")),
            fn("media_control", "Control media playback: play, pause, next, previous.", s("action", "play|pause|next|previous"), required = listOf("action")),
            // device
            fn("set_alarm", "Set an alarm.", i("hour", "0-23"), i("minute", "0-59"), s("label", "Label"), required = listOf("hour", "minute")),
            fn("set_timer", "Start a countdown timer.", i("seconds", "Seconds"), s("label", "Label"), required = listOf("seconds")),
            fn("set_volume", "Set media volume 0-100 or mute.", i("percent", "0-100"), required = listOf("percent")),
            fn("set_brightness", "Set screen brightness 0-100.", i("percent", "0-100"), required = listOf("percent")),
            fn("flashlight", "Turn the torch on/off.", b("on", "true = on"), required = listOf("on")),
            fn("open_settings", "Open a settings panel: wifi, bluetooth, mobile_data, airplane, hotspot, location, display, sound, battery, apps, accessibility, notifications, assistant.", s("panel", "Panel"), required = listOf("panel")),
            fn("screen_power", "Turn screen on (wake) or off (lock), or open the phone power menu (power off/restart).", s("action", "wake|lock|power_menu|power_off|restart"), required = listOf("action")),
            // v1.3.1: screen video recording
            fn("record_screen", "Record the phone screen as a video (MP4, saved in Gallery > Movies > JarvisAI, with mic audio when allowed). First time Android asks the user to tap 'Start now' once — tell them that. 'start' begins recording (optionally auto-stops after N seconds), 'stop' stops it and returns the saved video location.", s("action", "start|stop"), i("seconds", "Auto-stop after N seconds (0 = record until stopped)"), required = listOf("action")),
            // v1.2: Admin lock / unlock, browser, disco, hosting, background upload
            fn("lock_screen", "Lock the phone screen now using Device Admin (needs Device Admin enabled in Jarvis Settings)."),
            fn("unlock_phone", "Wake the screen and try to show Jarvis. NOTE: if the phone has a PIN/password/fingerprint lock, only the owner can unlock it with that PIN/fingerprint — no app can bypass it. This only wakes the screen and opens Jarvis after the owner unlocks."),
            fn("open_browser", "Open the in-app browser on Google or a URL, render the page/search results there.", s("query", "Google search text or URL"), s("url", "Optional full URL")),
            fn("google_search", "Google search and render the results in the Jarvis in-app browser (not just Chrome).", s("query", "What to search"), required = listOf("query")),
            fn("disco_light", "Turn the disco edge light on/off. When on, a rainbow light runs around the display edges while Jarvis runs in the background.", b("on", "true = on"), required = listOf("on")),
            fn("upload_video", "Upload the newest gallery video in the background (needs an upload URL in Settings, or it will open the share screen).", i("index", "0 = newest video"), s("caption", "Optional caption")),
            fn("publish_file", "Publish a workspace file to your GitHub repo (Settings > GitHub token + owner/repo) and return the GitHub Pages link.", s("path", "Relative workspace path e.g. index.html"), required = listOf("path")),
            fn("host_website", "Start/stop hosting your workspace website on this phone over Wi-Fi (http://phone-ip:8080).", b("on", "true = start, false = stop"), required = listOf("on")),
            fn("phone_status", "This phone's own status: battery, network, time, storage and current app."),
            // files & coding
            fn("save_file", "Save a file (code in any language, website html/css/js, notes, etc.). Saved in the app workspace and in Downloads/JarvisAI.", s("path", "Relative path e.g. myapp/index.html"), s("content", "Full file content"), required = listOf("path", "content")),
            fn("read_file", "Read a file from the workspace.", s("path", "Relative path"), required = listOf("path")),
            fn("list_files", "List files in the workspace.", s("dir", "Sub folder (optional)")),
            fn("delete_file", "Delete a workspace file.", s("path", "Relative path"), required = listOf("path")),
            fn("preview_html", "Open a saved HTML file (website/web app/game) in the built-in preview browser.", s("path", "Relative path"), required = listOf("path")),
            fn("run_javascript", "Run JavaScript code and return console output/result (for calculations, testing code logic).", s("code", "JS code"), required = listOf("code")),
            // memory
            fn("remember", "Save a long-term memory fact about the user.", s("fact", "Fact"), required = listOf("fact")),
            fn("forget", "Delete memories containing a keyword.", s("keyword", "Keyword"), required = listOf("keyword")),
            fn("search_history", "Search past conversation history.", s("query", "Text"), required = listOf("query")),
            // v1.3.9: battery, translate, scheduling, routines, security, diagnostics, sync, theme, resources, prediction, updates, integrity, API, logs
            fn("battery_status", "Full battery report: level, charging/discharging, health, temperature, voltage, plugged type."),
            fn("health_status", "Today's health summary from the phone's own sensors: steps, distance, calories estimate, heart-rate sensor, Health Connect status. Sleep/weight/BP from other apps cannot be read in this version."),
            fn("health_permissions", "Open the Health Connect permission screen so you can grant Jarvis health access (steps, heart rate, sleep, exercise)."),
            fn("log_sleep", "Log last night's sleep: bed time and wake time (e.g. '11:30pm' and '7:00am'). Jarvis tracks sleep duration and the 7-day average.", s("bed", "Bed time, e.g. '11:30pm'"), s("wake", "Wake time, e.g. '7:00am'"), required = listOf("bed", "wake")),
            fn("log_water", "Log drinking water (e.g. '1 glass', '2 glass', '500ml', '1 liter'). Goal is 2000ml (8 glasses) a day.", s("amount", "Amount: '1 glass', '500ml', '1 liter'"), required = listOf("amount")),
            fn("wellness_status", "Show the wellness summary: last night's sleep, 7-day sleep average, and today's water intake vs the 2000ml goal."),
            fn("translate", "Translate text to another language in real time (Bengali, English, Hindi, Spanish, Arabic, French, any language).", s("text", "Text to translate"), s("target", "Target language, e.g. Bengali, English, Hindi"), required = listOf("text", "target")),
            fn("translate_offline", "Translate text or a .txt/.md file OFFLINE between Bengali and English using the built-in dictionary — no internet needed. Word-by-word only; for full sentence translation use the online 'translate' tool when online.", s("text", "Text or workspace filename, e.g. notes.txt"), s("target", "Target language: Bengali or English"), required = listOf("text", "target")),
            fn("save_password", "Save a password in the encrypted on-device vault (AES-256-GCM). Only the site name and username are ever shown — passwords are never spoken aloud or shared.", s("site", "Site or app, e.g. Facebook"), s("username", "Username or email"), s("password", "The password to store"), required = listOf("site", "password")),
            fn("get_password", "Get a saved password from the encrypted vault so Jarvis can type it into a login form. Never speak it aloud in voice mode.", s("site", "Site or app name"), required = listOf("site")),
            fn("list_passwords", "List the sites stored in the password vault (names only — passwords are never shown in a list)."),
            fn("delete_password", "Delete a saved password from the vault.", s("site", "Site or app name"), required = listOf("site")),
            fn("add_wake_word", "Give a family member their own wake word: when they say it, Jarvis wakes and knows who is talking. Example: wake word 'ammu dakche', person Ammu.", s("word", "Wake word, e.g. ammu dakche"), s("person", "Person name, e.g. Ammu"), required = listOf("word", "person")),
            fn("list_wake_words", "List all family wake words and who they belong to."),
            fn("remove_wake_word", "Remove a family wake word.", s("word", "Wake word to remove"), required = listOf("word")),
            fn("schedule_task", "Schedule a reminder: Jarvis notifies you at that time.", s("text", "Reminder text"), s("time", "When: 'in 30 minutes', '18:30', '6:30pm', 'tomorrow 9:00'"), b("daily", "Repeat every day"), required = listOf("text", "time")),
            fn("list_schedules", "List all scheduled reminders and routines."),
            fn("list_events", "List calendar appointments: today, tomorrow, this week, or a date (2026-11-16). Reads the phone's calendar (Google Calendar syncs to it).", s("when", "today / tomorrow / this week / 2026-11-16 (default today)")),
            fn("add_event", "Add an appointment to the phone's calendar. Warns about clashes with existing appointments.", s("title", "Event title"), s("start", "When: 'in 30 minutes', '18:30', '6:30pm', 'tomorrow 9:00'"), i("duration", "Duration in minutes (default 60)"), s("description", "Notes"), s("location", "Where"), required = listOf("title", "start")),
            fn("daily_briefing", "Generate the daily summary: weather, today's calendar appointments, today's reminders, and top news headlines. Also saved to workspace/daily-briefing-<date>.md. Can be scheduled as a daily routine with action 'briefing'."),
            fn("sort_memes", "Auto-sort a meme folder: images/gifs/videos go into type + month subfolders (images/2026-11/), exact duplicates move to Duplicates/ (never deleted). Default folder is workspace/memes; 'downloads' / 'pictures' / any path also works. Shared folders need All files access.", s("folder", "Folder: memes, downloads, pictures, or a path (default memes)"), b("dry_run", "Only show what would happen, move nothing")),
            fn("focus_report", "Catch work-slacking on THIS phone: which apps were used during work hours, with video/social/games called out by name and a verdict. Needs Usage Access permission (Jarvis opens the settings page if it is off).", s("date", "'today' or 'yesterday'"), s("work_start", "Work starts, e.g. '10:00'"), s("work_end", "Work ends, e.g. '18:00'")),
            fn("network_status", "Check whether Jarvis is online or offline right now, and on what (Wi-Fi / mobile data). Jarvis switches automatically: internet drops -> offline mode, internet returns -> online mode."),
            fn("open_control_panel", "Open the phone's Control Panel (Quick Settings) — Wi-Fi, Bluetooth, torch, Do Not Disturb, brightness, volume, all the tiles. Needs Accessibility ON."),
            fn("toggle_setting", "Toggle a Control Panel setting like a human: Wi-Fi, Bluetooth, Aeroplane mode, Location, Mobile data, Hotspot, Dark mode, Eye Protection, Battery Saver (tapped on the panel), Torch (direct), Do Not Disturb (needs Notification access), Brightness / Auto-rotate (need Modify system settings once), Volume (direct). Android forbids silent system toggles — the panel tap is the honest way.", s("setting", "Setting name, e.g. Wi-Fi, Bluetooth, Torch, Do Not Disturb, Brightness, Volume"), i("level", "For Brightness/Volume: 0-100"), required = listOf("setting")),
            fn("set_wallpaper", "Change the phone wallpaper directly in the background (no UI opens) — home screen, lock screen, or both. Source: 'random' for a beautiful photo from the internet, an image URL, or a file in the workspace (workspace/ or workspace/wallpapers/).", s("source", "random, an image URL, or a workspace filename"), s("screen", "home, lock, or both (default)")),
            fn("schedule_message", "Automatic messaging system: schedule an SMS that Jarvis sends by itself at a set time, once or daily (e.g. 'prottek din sokal 8tay Ammu-ke Good morning'). Confirm recipient + message first — SMS goes to a real person. WhatsApp scheduled messages are not possible (no official API) — SMS is the automatic route.", s("to", "Contact name or phone number"), s("message", "The message text"), s("time", "When: 'in 30 minutes', '18:30', 'tomorrow 9:00'"), b("daily", "Repeat every day"), required = listOf("to", "message", "time")),
            fn("wake_screen", "Turn the phone screen ON (lock screen appears). Honest limit: no app can enter the PIN/password to unlock — only the user can, or Extend Unlock (Smart Lock) keeps it unlocked at trusted places/devices."),
            fn("backend_status", "Check the owner backend status: whether a backend URL is set, the last heartbeat result (ok / banned / suspended / shutdown), and this phone's install ID. Without a backend URL Jarvis runs fully locally."),
            fn("verify_owner", "Face check: take a front-camera selfie and compare it with the owner's saved photo (Gemini Vision). Answers whether the person holding the phone is the owner. Needs the owner photo + Gemini API key."),
            fn("save_owner_photo", "Save the owner's reference photo: takes a front-camera selfie and stores it as the owner photo for face check. The owner should look at the camera in good light."),
            fn("cancel_schedule", "Cancel a scheduled reminder or routine by its id.", s("id", "Schedule id from list_schedules"), required = listOf("id")),
            fn("create_routine", "Create an automation routine: a trigger + an action Jarvis runs by itself.", s("name", "Routine name"), s("trigger", "'daily 07:00' or 'charging'"), s("action", "'speak:<text>' | 'device:<entity>:on|off' | 'lock'"), required = listOf("name", "trigger", "action")),
            fn("list_routines", "List automation routines."),
            fn("delete_routine", "Delete a routine by its id.", s("id", "Routine id from list_routines"), required = listOf("id")),
            fn("security_scan", "Run an advanced security scan: screen lock, encryption, unknown sources, and which apps hold dangerous permissions (SMS, mic, camera, location, contacts)."),
            fn("diagnostics", "Deep device diagnostics without root: battery, storage, memory, CPU, Android version, security patch, network, uptime."),
            fn("troubleshoot", "Automated troubleshooting: diagnose problems and fix what can be fixed automatically (cache, history, low battery, crashes), report the rest."),
            fn("sync_upload", "Sync Jarvis memory + settings to the connected GitHub repo (private repo recommended; API keys never sync)."),
            fn("sync_download", "Download Jarvis memory + settings from the connected GitHub repo and apply them (restart Jarvis after)."),
            fn("backup_preferences", "Cloud backup of user preferences only (to the connected GitHub repo; no API keys)."),
            fn("restore_preferences", "Restore user preferences from the cloud backup."),
            fn("session_handoff", "Hand this session off to the user's other device: uploads the conversation so the other device can continue it."),
            fn("change_theme", "Change Jarvis accent colour/theme.", s("color", "cyan|orange|pink|green|purple"), required = listOf("color")),
            fn("resource_status", "Adaptive resource management report: battery, memory, storage, and what Jarvis adapted automatically."),
            fn("clean_cache", "Clean the app cache to free memory and storage."),
            fn("predict_next", "Predictive AI: what the user will probably want next, from their behaviour patterns (time of day + habits)."),
            fn("check_update", "Check whether a newer Jarvis build is available on GitHub Actions and download it if there is one."),
            fn("check_models", "Check which Gemini AI models are available right now and whether a newer one exists to switch to."),
            fn("verify_integrity", "Verify the blockchain-style integrity chain: proves Jarvis's own data was not tampered with."),
            fn("api_call", "Call any REST API (Zapier, IFTTT, Notion, Telegram, your own server...).", s("method", "GET|POST|PUT|PATCH|DELETE"), s("url", "Full https URL"), s("headers", "JSON object of headers, e.g. {\"Authorization\":\"Bearer x\"}"), s("body", "JSON body for POST/PUT/PATCH"), required = listOf("method", "url")),
            fn("view_logs", "Read Jarvis's own logs: startup log + last crash log (for debugging)."),
            fn("self_heal", "Run the self-healing check: recover from repeated crashes, trim oversized history, enter safe mode if needed."),
            fn("build_bundle", "Build an Android App Bundle (.aab) with Gradle on GitHub Actions — for publishing on the Play Store (AAB cannot be installed directly, it is for Play Store upload)."),
            fn("dashboard", "Open the Jarvis dashboard: live battery, memory, storage, CPU, network, mood, top tools, predictions, schedules, security."),
            fn("network_audit", "Audit the security of the user's OWN Wi-Fi and phone (open Wi-Fi? VPN on? Jarvis web server exposed?). Never scans anyone else's network."),
            fn("export_memory", "Export the user's whole Jarvis memory (facts + chat history + settings, no API keys) as one JSON file into Downloads > JarvisAI."),
            fn("set_gesture", "Teach Jarvis what a gesture means: map the phone shake to an action.", s("gesture", "'shake'"), s("action", "'open YouTube' | 'lock' | 'torch on' | 'torch off' | 'live'"), required = listOf("gesture", "action")),
            fn("delegate_task", "Delegate a task to Jarvis's background worker: it researches/drafts it, saves the result to the workspace, and notifies when done — the chat stays free meanwhile.", s("task", "What to do, e.g. 'Research the best budget phones under 20000 and save a comparison'"), required = listOf("task")),
            fn("fix_build", "Self-correcting deployment: diagnose the last failed GitHub Actions build from its logs, auto-fix what can be fixed, and retry."),
            fn("keep_alive", "Keep Jarvis running persistently in the background with the microphone always available: checks battery optimization and opens the exemption screen if needed."),
        ))
    }
}
