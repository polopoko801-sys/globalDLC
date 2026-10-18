package thunder.hack.gui.mainmenu;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Microsoft (premium) login via the OAuth 2.0 device-code flow:
 * MSA device code → poll token → Xbox Live → XSTS → Minecraft token → profile.
 * Runs off-thread; reports progress through {@link Callback}.
 *
 * NOTE: needs an Azure app client id with device-code flow + XboxLive.signin scope enabled.
 * If {@link #CLIENT_ID} isn't accepted by Microsoft you must register a free Azure app and put its id here.
 */
public final class MicrosoftAuth {
    // Public client id commonly used by open-source Minecraft launchers for the device-code flow.
    public static final String CLIENT_ID = "00000000402b5328";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    // Starszy flow login.live.com z tym scope zwraca refresh_token SAM Z SIEBIE (bez offline_access —
    // dodanie go potrafi zepsuc zadanie device code). Refresh token pozwala zalogowac sie PONOWNIE na to
    // konto bez wpisywania loginu/hasla Microsoftu.
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    public interface Callback {
        void onCode(String userCode, String verificationUri);
        void onStatus(String msg);

        /** @param refreshToken token do cichego ponownego logowania; pusty, gdy Microsoft go nie zwrocil */
        void onSuccess(String name, UUID uuid, String mcToken, String refreshToken);
        void onError(String msg);
    }

    public static void loginAsync(Callback cb) {
        Thread t = new Thread(() -> {
            try {
                login(cb);
            } catch (Exception e) {
                cb.onError(String.valueOf(e.getMessage()));
            }
        }, "GOMBAL-MS-Auth");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Loguje ponownie na konto premium z zapisanego refresh tokenu — BEZ hasla i bez kodu w przegladarce.
     * To jest sposob na "wejscie na konto, gdy sie zapomni hasla": token dziala dalej, nawet jak nie
     * pamietasz hasla do Microsoftu.
     */
    public static void refreshAsync(String refreshToken, Callback cb) {
        Thread t = new Thread(() -> {
            try {
                if (refreshToken == null || refreshToken.isBlank()) {
                    cb.onError("brak zapisanego tokenu — zaloguj sie przez Microsoft");
                    return;
                }
                cb.onStatus("odswiezam sesje...");
                JsonObject tok = form("https://login.live.com/oauth20_token.srf",
                        "client_id=" + CLIENT_ID + "&grant_type=refresh_token&scope=" + enc(SCOPE)
                                + "&refresh_token=" + enc(refreshToken));
                if (!tok.has("access_token")) {
                    cb.onError("token wygasl — zaloguj sie przez Microsoft");
                    return;
                }
                String msToken = tok.get("access_token").getAsString();
                String newRefresh = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : refreshToken;
                complete(msToken, newRefresh, cb);
            } catch (Exception e) {
                cb.onError(String.valueOf(e.getMessage()));
            }
        }, "GOMBAL-MS-Refresh");
        t.setDaemon(true);
        t.start();
    }

    private static void login(Callback cb) throws Exception {
        // 1) device code — login.live.com flow (works with the public Minecraft client id)
        JsonObject dev = form("https://login.live.com/oauth20_connect.srf",
                "client_id=" + CLIENT_ID + "&scope=" + enc(SCOPE) + "&response_type=device_code");
        if (!dev.has("device_code")) {
            cb.onError("device code: " + dev);
            return;
        }
        String deviceCode = dev.get("device_code").getAsString();
        String userCode = dev.get("user_code").getAsString();
        String verUri = dev.has("verification_uri") ? dev.get("verification_uri").getAsString() : "https://login.live.com/oauth20_remoteconnect.srf";
        int interval = dev.has("interval") ? dev.get("interval").getAsInt() : 5;
        cb.onCode(userCode, verUri);

        // 2) poll for the MSA token
        String msToken = null, refreshToken = "";
        long deadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonObject tok = form("https://login.live.com/oauth20_token.srf",
                    "client_id=" + CLIENT_ID + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                            + "&device_code=" + deviceCode);
            if (tok.has("access_token")) {
                msToken = tok.get("access_token").getAsString();
                if (tok.has("refresh_token")) refreshToken = tok.get("refresh_token").getAsString();
                break;
            }
            String err = tok.has("error") ? tok.get("error").getAsString() : "";
            if (err.equals("authorization_pending")) continue;
            if (err.equals("slow_down")) {
                interval += 2;
                continue;
            }
            cb.onError("MS: " + (err.isEmpty() ? tok.toString() : err));
            return;
        }
        if (msToken == null) {
            cb.onError("timeout logowania Microsoft");
            return;
        }

        complete(msToken, refreshToken, cb);
    }

    /** Wspolna sciezka Xbox Live → XSTS → Minecraft → profil, uzywana i przy logowaniu, i przy odswiezaniu. */
    private static void complete(String msToken, String refreshToken, Callback cb) throws Exception {
        // 3) Xbox Live — RpsTicket is the live.com token directly (no "d=" prefix)
        cb.onStatus("Xbox Live...");
        JsonObject xbl = json("https://user.auth.xboxlive.com/user/authenticate",
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\""
                        + msToken + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
        String xblToken = xbl.get("Token").getAsString();
        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

        // 4) XSTS
        cb.onStatus("XSTS...");
        JsonObject xsts = json("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken
                        + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}");
        String xstsToken = xsts.get("Token").getAsString();

        // 5) Minecraft token
        cb.onStatus("Minecraft...");
        JsonObject mcj = json("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}");
        String mcToken = mcj.get("access_token").getAsString();

        // 6) profile
        HttpResponse<String> pr = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + mcToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject prof = GSON.fromJson(pr.body(), JsonObject.class);
        if (prof == null || !prof.has("id")) {
            cb.onError("To konto nie ma Minecrafta");
            return;
        }
        String name = prof.get("name").getAsString();
        String id = prof.get("id").getAsString();
        UUID uuid = UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        cb.onSuccess(name, uuid, mcToken, refreshToken == null ? "" : refreshToken);
    }

    private static JsonObject form(String url, String body) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return GSON.fromJson(r.body(), JsonObject.class);
    }

    private static JsonObject json(String url, String body) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return GSON.fromJson(r.body(), JsonObject.class);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
