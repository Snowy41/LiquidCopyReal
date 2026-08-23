# Microsoft browser login setup

LiquidCopy authenticates directly with Microsoft, Xbox Live, and Minecraft
Services. It does not start, read credentials from, or depend on the official
Minecraft Launcher.

The launcher uses the OAuth 2.0 authorization-code flow with PKCE. Login opens
in the operating system's default browser, so existing Microsoft website
cookies and browser-based account selection continue to work. The callback is
received only by a temporary loopback listener on this computer.

The authorization request explicitly uses `prompt=select_account`. Microsoft
therefore shows the browser's existing account chooser and can reuse a valid
session already stored by that browser. Netscape-format cookie-export text
files are website bearer sessions, not Minecraft access or refresh tokens;
LiquidCopy never parses or injects them. The supported path is to keep the
account signed in inside the selected default browser.

## Distributor registration

LiquidCopy ships with the distributor-owned public desktop Application ID.
Users never create an Entra registration, paste an ID, or supply a client
secret. The registration uses a personal-account audience and the
`http://localhost` system-browser redirect URI.

## Configure and sign in

1. Start `LiquidCopy-Launcher.jar` or `Launch LiquidCopy.cmd`.
2. Click **Use browser Microsoft account**.
3. Complete the Microsoft page in the browser. Existing browser cookies can
   select or authenticate an already signed-in account.
   If the browser association fails, use **Copy sign-in URL** and paste it into
   a browser. **Cancel sign-in** immediately stops the loopback wait.
4. The browser returns to a one-time `http://localhost:<port>/` URL.
   LiquidCopy validates the OAuth state and PKCE response, exchanges it through
   Xbox Live/XSTS and Minecraft Services, verifies game ownership, reads the
   Minecraft profile, and enables **Play**.

## Local files

The Windows data root is `%APPDATA%\LiquidCopy`. Launcher settings are written
to `launcher-settings.json`; the refreshable account session is written to
`microsoft-account.json`. On Windows that session payload is encrypted with
current-user DPAPI and cannot be opened by a different Windows account or
machine. Legacy plaintext schema-1 sessions migrate after a successful load.
On POSIX, owner-only directory/file modes are applied. Selecting **Sign out**
deletes the stored session.
