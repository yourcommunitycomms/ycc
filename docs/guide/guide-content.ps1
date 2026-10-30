# Content of the getting-started guide (used by makedoc.ps1).
$script:picId = 0
$W = 420   # shrink phone screenshots to this many pixels wide
$i = @{}
foreach ($n in "g01-callsign", "g02-publickey", "g03-purpose", "g04-group", "g05-permissions", "g06-allow", "g07-permsdone",
    "g08-battery", "g09-home", "g11-dryad", "g12-generate", "g13-channels", "g14-onpublic", "g15-settings", "g16-share",
    "h01-callsign", "h02-joinpublic", "h03-permissions", "h04-onpublic", "h05-accept", "h07-channels", "h08-onA",
    "i00-files", "i03-blocked", "i05-allowed", "i06-install", "i07-blockagain", "i02-unknown-old") {
    $i[$n] = AddImage $n $W
}

Add (P "Your Community Communications" "Title")
Add (P "Getting started: installing the app, setting up your group and getting everyone on the same channel" "Subtitle")

Add (P "Before you start: the network" "Heading1")
Note "**Put every phone on the same wireless network: one you trust, that does not use client isolation, and that lets broadcast traffic through.**"
Add (P "The app needs no server and no accounts. Phones find each other by sending to everyone on the local network, so the network has to let them do that:")
Add (P "• **Same network:** every phone joins the same WiFi (or the same OpenMANET mesh). A phone's own hotspot works well for a small group in the field.")
Add (P "• **No client isolation:** many guest, hotel, café and some home routers stop devices talking to each other (called client isolation, AP isolation or guest mode). Turn it off, or use another network.")
Add (P "• **Broadcast allowed:** the router must pass broadcast (or multicast) traffic between devices. Most home routers and phone hotspots do; some business and public WiFi does not.")
Add (P "• **A network you trust:** your channels are encrypted, but anyone on the network can see that the phones are talking. The Public channel is not private at all.")
Add (P "No internet is needed. Maps can work offline, and nothing is uploaded anywhere. An optional **LAN storage server** on your own network (for example on Project NOMAD) keeps shared map tiles, app updates and the group's settings, and makes setting up new phones a single QR scan (section 3).")

Add (P "1. Installing the app" "Heading1")
Add (P "The app is not in an app store. You install the file your-community-communications.apk directly (sideloading). Copy it to the phone (USB cable, a download link, a memory card, or a file share), or with a LAN storage server open **http://<server>:8647/YCC/Updates/YCC.apk** in the phone's browser, then:")
Add (P "Android 8 and newer" "Heading2")
Step @("**1.** Open the Files (or Downloads) app and tap **your-community-communications.apk**.") @($i["i00-files"])
Step @("**2.** Android blocks apps from unknown sources at first. Tap **Settings**.") @($i["i03-blocked"])
Step @("**3.** Turn on **Allow from this source**, then press **Back**.") @($i["i06-install"])
Step @("**4.** Tap **Install** (or **Update** if an older copy is already on the phone) and wait for it to finish.") @($i["i05-allowed"])
Step @("**5.** Go back to that same **Install unknown apps** setting (Settings > Apps > Special app access > Install unknown apps, then the Files app) and turn **Allow from this source** off again, so nothing else can install itself.") @($i["i07-blockagain"])
Add (P "Android 4.1 to 7" "Heading2")
Step @("**1.** Open **Settings > Security** and tick **Unknown sources**.", "**2.** Open the APK in your file manager or Downloads and tap **Install**.", "**3.** Go back to **Settings > Security** and untick **Unknown sources** again.") @($i["i02-unknown-old"])
Note "**Setting up lots of phones?** Turn on Developer options and USB debugging on each phone and plug it into a computer. An AI assistant (or anyone with adb) can then install the app and allow every permission and setting in one go."

Add (P "2. The first phone: set up the group" "Heading1")
Add (P "One person (the group leader) does this once. Their phone creates the group's DRYAD sheet, whose rows become the private channel names, and later shares everything with the other phones.")
Step @("**1. Callsign.** Open the app. Type the name others will see when you talk or send, then tap **Next**. At **Quick set from a QR code** tap **Skip quick set** (the first phone has nobody to copy from).") @($i["g01-callsign"])
Note "**How will you use it?** After the callsign the app asks for a mode. Pick **Advanced** for this guide (DRYAD sheets, channels A and B). The simple modes (Scouts, Staff, 4WDing, Friends convoy, Caravan / camping) and **Intercom** only ask for a private key (everyone types or scans the same one) and the public key, then join straight away."
Step @("**2. Public channel key.** Leave it as it is unless your group uses its own. Everyone with the same key shares the Public channel. Tap **Next**.") @($i["g02-publickey"])
Step @("**3. What is your group for?** Pick Friends and family, Security, Firefighting or Military. This sets the quick map-point buttons and is shared with the others. Tap **Next**.") @($i["g03-purpose"])
Step @("**4. Set up your group.** Tap **Create sheet**. The app makes a never-expiring DRYAD sheet: row A becomes channel A and row B channel B.") @($i["g04-group"])
Step @("**5. Permissions.** The Permissions screen opens. Tap **Allow all** and allow each one (choose **While using the app** where asked).") @($i["g05-permissions"], $i["g06-allow"])
Step @("**6. Keep it running when the phone is locked.** Tap **Turn battery optimisation off** and choose **Allow**. Follow any extra tips shown for your phone's brand. Then tap the gold **house** to go to the home screen.") @($i["g07-permsdone"], $i["g08-battery"])
Step @("**7. The home screen.** Channel buttons along the top, **Channels**, **Contacts**, your status, quick map points, Call, Silent, Chat and the big **PUSH TO TALK**.") @($i["g09-home"])
Step @("**8. Your DRYAD sheet.** Menu (three dots) > **Modern DRYAD** shows the sheet that was just made. **Generate** makes more sheets (for example one per day), and **→ A**, **→ B** and **→ X** put any row onto a channel.") @($i["g11-dryad"], $i["g12-generate"])
Step @("**9. Join the Public channel.** Tap **Channels**, then **Talk here** next to **Public**. The other phones will meet you there.") @($i["g13-channels"], $i["g14-onpublic"])
Note "**Have a LAN storage server?** On this phone: Settings > **LAN storage location** (for example http://<server>:8647/YCC, the user and password, **Test**). Then every other phone can use the quick way in section 3: show them Settings > **Phone setup QR code (for new phones)**, or the code on the server's admin page. To give new phones this phone's settings too, turn on **LAN sync: config**, tap **Sync now**, and on the admin page choose **Defaults > Use as defaults** on its backup."

Add (P "3. Every other phone, the quick way: scan the phone setup QR code" "Heading1")
Add (P "Use this when your group has a LAN storage server, or when a phone that is already set up can show its setup code.")
Add (P "• **Where the code comes from:** the server's admin page (**http://<server>:8647/webadmin**, tab **Phone setup**, printed or on a screen), or a set-up phone: menu > **Settings** > **Phone setup QR code (for new phones)**, then **With channel A** or **Without**.")
Step @("**1.** Install the app (section 1) and open it. Type a callsign and tap **Next**.") @($i["h01-callsign"])
Add (P "**2.** At **Quick set from a QR code**, tap **Scan QR code** and point the camera at the setup code.")
Add (P "**3.** The phone lists what it will set (LAN storage, tunnel server, Public key, channel A, OpenMANET, home WiFi, and the group's defaults). Tap **Set up**. It checks it can log in to the LAN storage, then loads the group's settings and DRYAD sheets.")
Add (P "**4.** Allow the permissions as on the first phone, then tap **Channels** and **Talk here** on channel A (or the channel your group uses). Done: skip sections 4 to 6.")
Note "**The setup code holds the LAN storage password** (and channel A if included). Only show it to your own group. No camera, or no server? Tap **Skip quick set** and use sections 4 to 6."
Add (P "4. Every other phone without a setup code: join Public" "Heading1")
Step @("**1.** Install the app (section 1) and open it. Type a callsign, tap **Skip quick set**, then **Next** on the Advanced or simple note, pick **Advanced**, and tap **Next** through the public key and group questions.") @($i["h01-callsign"])
Step @("**2.** At **Set up your group**, tap **Join Public**. Do not create a sheet: the leader's sheet is coming.") @($i["h02-joinpublic"])
Step @("**3.** Allow the permissions on the Permissions screen as on the first phone (on Android 4.1 to 5 they are already allowed), then go back.") @($i["h03-permissions"])
Step @("**4.** The phone is now on Public. **Contacts (1)** shows the leader has been heard.") @($i["h04-onpublic"])

Add (P "5. The leader sends the settings and DRYAD sheets" "Heading1")
Step @("**1.** On the leader's phone: menu (three dots) > **Settings**, scroll down to **Share these settings and DRYAD sheets with the channel…** and tap it.", "(The address under Network in this picture is only for the test emulators. On real phones leave Extra peers empty.)") @($i["g15-settings"])
Step @("**2.** The app warns that everyone on Public would receive your channel names. Only do this on a network you trust, with your own group listening. Tap **Send**.") @($i["g16-share"])
Step @("**3.** On each other phone: **Settings from LEADER1** appears. Tap **Clear and accept**. The phone gets channels A and B, the DRYAD sheet and the group's options; its own callsign stays.") @($i["h05-accept"])

Add (P "6. Everyone moves to channel A" "Heading1")
Step @("**1.** On every phone: tap **Channels**, then **Talk here** next to **Channel A**.") @($i["h07-channels"])
Step @("**2.** You are on your private, encrypted channel. **Contacts** shows who is on it. Hold **PUSH TO TALK** to talk; everyone on channel A hears you, and you keep hearing them.") @($i["h08-onA"])

Add (P "Good to know" "Heading1")
Add (P "• **Leaving a channel** is on the Channels page (red **Leave**), so nothing on the home screen can drop you off air by accident.")
Add (P "• **Call** rings a person or everyone on the channel; **Silent** stops the phone sending anything.")
Add (P "• **Status** (the Available button): Away saves voice for later; Stealth (or turning the volume all the way down, or silent/vibrate) silences the phone and buzzes instead.")
Add (P "• **New phones later:** scan the phone setup QR code in the wizard (section 3), or Settings > **Scan QR** on a phone already in use. Channel QR codes (Settings > QR of A) share just one channel.")
Add (P "• **LAN storage server:** Settings > **LAN storage location** points a phone at it (address, user, password, **Test**). Shared map tiles, auto updates, logs, missions and config backups are in Settings > **LAN sync and updates**. Installing the server on Project NOMAD and running the commander view on an Ubuntu desktop: see **NOMAD-SETUP.md**.")
Add (P "• **Every feature** is listed in **ALL-FEATURES.txt**, and explained in the user guide **FEATURES.md**.")
Add (P "• **Full map:** the buttons at the top left are **Search** (places, offline country lists can be downloaded), **Layer** (street, satellite or topographic) and **Tools** (a ruler: tap two points for the distance and bearing). The scale bar sits under them. The map menu has **Data Tile Management** to save a 5, 10 or 20 km area for offline use.")
Add (P "• **Tablets** held sideways show the commander view: map, contacts and channels together.")
Add (P "• **Lost phone?** Set a PIN in Settings and turn on lost-device protection: two wrong PINs erase the app's data.")
