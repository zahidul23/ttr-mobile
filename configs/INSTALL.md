# TTR Mobile controls for BannerHub 3.8.1

`ttr-mobile-controls.icp` gives BannerHub the TTR Mobile landscape layout: joystick bottom left, Jump and Throw bottom right,
Map / Gags / Cam in a row at the bottom centre. It only changes the touch controls; TTR itself is set up in
BannerHub as usual.

## Import

1. Copy `ttr-mobile-controls.icp` to Downloads on your phone.
2. Start TTR in BannerHub and open the sidebar (back button or gesture) → Controls → Switch Touch Control Layout
   → More Layouts.
3. Tap **Import**, pick the file, then **Apply** next to **Toontown Rewritten Mobile**.

Do not use the game page's Import Config command: that expects a different JSON format.

## Keys

| Button | Key | In TTR |
|---|---|---|
| Joystick | arrow keys | move (eight ways) |
| Jump | Left Ctrl | jump |
| Throw | Delete | throw a pie |
| Map | Left Alt | map |
| Gags | Home | gags page |
| Cam | Tab | change camera |

Keep TTR's default key bindings, or change the profile to match yours. There is no Tasks button: TTR opens
Tasks with End, and BannerHub cannot bind End. Use the Shticker Book for tasks. Typing uses BannerHub's
keyboard (sidebar keyboard icon).

## Running the installed launcher instead of the installer

BannerHub's Startup File Path picker only browses phone storage, while the installer puts TTR in the container's
own `C:\Program Files (x86)\Toontown Rewritten`. Either reinstall to `D:\Toontown Rewritten` (D: is the phone's
Download folder), or copy that folder to D: from Game Settings → Enter Container Desktop. Then set Startup File
Path to `Download/Toontown Rewritten/Launcher.exe`.
