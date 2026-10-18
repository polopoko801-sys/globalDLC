package thunder.hack.features.modules.misc.rynek;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts every confirmed purchase to a Discord webhook: what was bought, for how much, with which
 * enchantments, the balance left, and where the session stands.
 * <p>
 * Everything runs on one background thread. A snipe run buys in bursts, and Discord rate-limits
 * webhooks at a handful of calls a second — doing this on the render or tick thread would stutter the
 * game exactly while you are clicking, so posts are queued and spaced out instead. If the queue ever
 * runs away (a very long burst, or Discord being slow) new entries are dropped rather than allowed to
 * pile up: a missing log line is a nuisance, a growing queue is a leak.
 */
public final class RynekWebhook {
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "McRynek-Webhook");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** Discord's own floor is 5 requests/second per webhook; stay well under it. */
    private static final long SPACING_MS = 260L;
    private static final int MAX_QUEUED = 40;
    /** Discord green. */
    private static final int COLOR = 0x57F287;

    private static volatile int queued;

    private RynekWebhook() {
    }

    /**
     * Queues one purchase. Does nothing when no URL is configured, so the feature costs nothing until
     * the user pastes a webhook in.
     */
    public static void postPurchase(String url, String item, int qty, long price, List<String> enchants,
                                    long balance, long sessionSpent, int sessionItems, String sessionTime) {
        if (url == null || url.isBlank() || !url.startsWith("http")) return;
        if (queued >= MAX_QUEUED) return;

        String name = item == null || item.isBlank() ? "?" : item;
        String heading = (qty > 1 ? "**" + qty + "x " + name + "**" : "**" + name + "**");

        // enchantments as inline code chips, so a long list still reads as a list
        StringBuilder desc = new StringBuilder(heading);
        if (enchants != null && !enchants.isEmpty()) {
            desc.append("\n");
            for (int i = 0; i < enchants.size() && i < 12; i++) {
                if (i > 0) desc.append(" ");
                desc.append('`').append(enchants.get(i)).append('`');
            }
        }

        String json = "{"
                + "\"username\":\"McRynek\","
                + "\"embeds\":[{"
                + "\"title\":\"🛒  Kupiono\","
                + "\"description\":" + quote(desc.toString()) + ","
                + "\"color\":" + COLOR + ","
                + "\"fields\":["
                + field("Cena", "**$" + money(price) + "**", true)
                + "," + field("Za sztukę", "$" + money(qty > 1 ? price / qty : price), true)
                + "," + field("Saldo", balance >= 0 ? "$" + money(balance) : "—", true)
                + "," + field("Sesja", "$" + money(sessionSpent) + " · " + sessionItems + " szt. · " + sessionTime, false)
                + "],"
                + "\"footer\":{\"text\":\"AutoMarket\"},"
                + "\"timestamp\":" + quote(Instant.now().toString())
                + "}]}";

        queued++;
        SENDER.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
                Thread.sleep(SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // a dead webhook must never interrupt a buying session
            } finally {
                queued--;
            }
        });
    }

    /** 1234567 -> "1 234 567". Long prices are unreadable as a run of digits. */
    private static String money(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + sb;
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":" + quote(name) + ",\"value\":" + quote(value) + ",\"inline\":" + inline + "}";
    }

    /** Minimal JSON string escaping — item names carry quotes, backslashes and colour junk. */
    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length() && sb.length() < 1400; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
