# Microsoft browser login setup

LiquidCopy authenticates directly with Microsoft, Xbox Live, and Minecraft
Services. It does not start, read credentials from, or depend on the official
Minecraft Launcher.

The launcher uses the OAuth 2.0 authorization-code flow with PKCE. Login opens
in the operating system's default browser, so existing Microsoft website
cookies and browser-based account selection continue to work. The callback is
received only by a temporary loopback listener on this computer.

## Register the desktop application

1. Open the Microsoft Entra admin center and create a new **App registration**.
2. Name it `LiquidCopy Launcher`.
3. Select an account audience that includes **personal Microsoft accounts**.
   The `consumers` authority used by LiquidCopy accepts personal accounts only.
4. Under **Authentication**, add the **Mobile and desktop applications**
   platform with the system-browser redirect URI `http://localhost`.
5. Treat it as a public/native client. Do not create or put a client secret in
   the launcher; PKCE proves the authorization request instead.
6. Ensure the distributor's registration is accepted/enabled for Xbox Live and
   Minecraft Services. An arbitrary generic Entra registration is not
   automatically sufficient.
7. Copy the registration's **Application (client) ID**. Use your own
   distributor registration; do not copy another launcher's client ID.

## Configure and sign in

1. Start `LiquidCopy-Launcher.jar` or `Launch LiquidCopy.cmd`.
2. Paste the Application (client) ID into **Microsoft application ID**.
3. Click **Save settings**, then **Sign in with Microsoft**.
4. Complete the Microsoft page in the browser. Existing browser cookies can
   select or authenticate an already signed-in account.
   If the browser association fails, use **Copy sign-in URL** and paste it into
   a browser. **Cancel sign-in** immediately stops the loopback wait.
5. The browser returns to a one-time `http://localhost:<port>/` URL.
   LiquidCopy validates the OAuth state and PKCE response, exchanges it through
   Xbox Live/XSTS and Minecraft Services, verifies game ownership, reads the
   Minecraft profile, and enables **Play**.

The same client ID may alternatively be supplied with the
`LIQUIDCOPY_MICROSOFT_CLIENT_ID` environment variable or the
`-Dliquidcopy.microsoft.clientId=<id>` Java system property.

## Local files

The Windows data root is `%APPDATA%\LiquidCopy`. Launcher settings are written
to `launcher-settings.json`; the refreshable account session is written to
`microsoft-account.json`. On Windows that session payload is encrypted with
current-user DPAPI and cannot be opened by a different Windows account or
machine. Legacy plaintext schema-1 sessions migrate after a successful load.
On POSIX, owner-only directory/file modes are applied. Selecting **Sign out**
deletes the stored session.
