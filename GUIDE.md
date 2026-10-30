# Telebisyon: quick guide

Telebisyon is a home screen for Android TV that you can also run from your phone. It's forked from [TV Detox](https://github.com/Ansub/tv-detox) by Ansub Khan.

## Install (once, from a computer)

You need a computer on the same Wi-Fi as the TV: a Mac, a Linux PC, or a Windows PC. You only need it for setup. Afterwards everything works from a phone.

### 1. Get the TV ready

1. On the TV: **Settings → Device Preferences → About**, then press **Build** 7 times until it says you're a developer.
2. Back in **Device Preferences → Developer options**: turn on **USB debugging** (and **Network debugging** if your TV has it).
3. Find the TV's IP address: **Settings → Network & Internet →** your Wi-Fi network. It looks like `192.168.1.50`.

### 2. Get the computer ready

Pick your computer. You only do this once. All you need is **adb**, Android's tool for talking to the TV.

<details open>
<summary><b>Mac</b></summary>

Install [Homebrew](https://brew.sh) if you don't have it, then in **Terminal**:

```sh
brew install android-platform-tools
```

</details>

<details>
<summary><b>Linux</b> (Ubuntu, Debian, Mint, and others)</summary>

In a terminal:

```sh
sudo apt update
sudo apt install -y adb curl unzip
```
(Fedora: `sudo dnf install -y android-tools curl unzip`.)

</details>

<details>
<summary><b>Windows</b> (10 or 11)</summary>

The scripts are made for Mac and Linux, so on Windows they run in **WSL**: Ubuntu inside Windows, from Microsoft.

1. Open **PowerShell as administrator** (right-click Start → Terminal (Admin)) and run:
   ```powershell
   wsl --install -d Ubuntu
   ```
2. Restart the PC when it asks. **Ubuntu** then opens by itself. Pick a user name and password.
3. In that Ubuntu window, follow the **Linux** steps above.
4. Put the Telebisyon folder somewhere Ubuntu can see it. Your Windows Downloads folder is `/mnt/c/Users/<your Windows name>/Downloads`, so for example:
   ```sh
   cp -r /mnt/c/Users/<your Windows name>/Downloads/telebisyon-* ~/telebisyon
   ```

Run every command from here on in the **Ubuntu** window, not PowerShell. It reaches the TV over Wi-Fi like any other computer.

</details>

**Download Telebisyon:** get **Source code (zip)** from the [latest release](https://github.com/knznepo/telebisyon/releases/latest) and unzip it.

### 3. Install everything

In Terminal (Mac), a terminal (Linux) or the Ubuntu window (Windows):

```sh
cd <Telebisyon folder>
sh setup.sh 192.168.1.50
```

This downloads the home screen, installs it, turns on the phone pages, sets the TV's clock, and copies any photos.

- The first time, the TV asks **"Allow USB debugging?"**. Tick **Always allow**, press **OK**, and run the command again if it stopped.
- If it asks to turn off the TV's own home screen, answer **y** so the Home button opens the new one. `sh undo.sh 192.168.1.50` puts everything back.
- **TVs with Google TV** (Sony BRAVIA from 2021, TCL, Hisense and others with the "For you" screen): Home keeps opening Google TV. If you're connected over USB or Network debugging (port 5555), run `GOOGLE_TV=1 YES_SETUP=1 sh setup.sh 192.168.1.50` to turn Google TV's home off, then restart the TV once. On a **Chromecast with Google TV** don't: open Telebisyon from the apps row instead. See [step 5 in the README](README.md#download-and-install) for the details.
- **Optional, recommended:** add a PIN for the phone pages and turn on the Files page: `PIN=123456 FILES=1 sh setup.sh 192.168.1.50`.
- **Optional:** put photos in the `wallpapers` folder inside the Telebisyon folder first, and they're copied to the TV. You can also add them later from your phone.

## Use it

### On the TV

| To… | Do this |
|---|---|
| Open an app | Select it and press **OK** |
| Move, replace or remove an app | Hold **OK** on it |
| Add an app | **Add app** tile |
| Change the wallpaper | **Settings** tile → **Wallpaper** |
| See your account settings | Select the round avatar next to the Wi-Fi icon |
| Leave the photo frame | Press any button |

### From a phone or computer on the same Wi-Fi

| Open… | To… |
|---|---|
| `http://192.168.1.50:7979` | See the TV's home screen and tap an app to open it on the TV. The **Message**, **Wallpapers** and **Files** tiles there open the other pages |
| `http://192.168.1.50:7979/message` | Send a message to the TV screen, with an optional chime |
| `http://192.168.1.50:7979/wallpapers` | Upload photos, pick the wallpaper, shuffle them, set up the photo frame, and set your profile picture |
| `http://192.168.1.50:7979/files` | Browse the TV and USB drives, upload (drag files onto the page), download, rename, delete, and install APKs. Off until you run `sh files.sh 192.168.1.50` |

Tip: add these to your phone's home screen (in the browser's Share menu, choose **Add to Home Screen**).

**Anyone on your Wi-Fi can use these pages unless you set a PIN:** `sh pin.sh 192.168.1.50 123456` (browsers ask for it once; `off` instead of a PIN removes it). To turn the pages off: `sh messages.sh 192.168.1.50 off`. To turn them back on: `sh messages.sh 192.168.1.50`. The Files page alone: `sh files.sh 192.168.1.50 off`.

### From Home Assistant

See **"Can Home Assistant control the TV?"** in [README.md](README.md#web-page) for ready-to-paste examples: pause, show a message, and a TV status sensor.

## If something's wrong

| Problem | Fix |
|---|---|
| The pages don't open | The TV must be on and on the same Wi-Fi. Run `sh messages.sh 192.168.1.50` again. |
| The TV's clock is wrong | `sh clock.sh 192.168.1.50` turns on internet time and sets your time zone. |
| The TV's IP address changed | Use the new one. Setting a fixed IP for the TV in your router stops this from happening. |
| Home opens the TV's old home screen | Run `sh setup.sh 192.168.1.50` again and answer **y** when asked. On Google TV, use `GOOGLE_TV=1 YES_SETUP=1` (see above). |
| The photo frame never shows | It needs at least one picture, and the TV's own screensaver may start first: set that to start later in the TV's settings. |
| The avatar shows the wrong letter, or none | It comes from the Google account on the TV when you install. Run `sh setup.sh 192.168.1.50` again after changing accounts, or upload a photo on the Wallpapers page. |
| Undo everything | `sh undo.sh 192.168.1.50` |
