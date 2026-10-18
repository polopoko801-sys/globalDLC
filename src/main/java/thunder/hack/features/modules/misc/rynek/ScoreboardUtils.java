package thunder.hack.features.modules.misc.rynek;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static thunder.hack.features.modules.Module.mc;

/**
 * Reads the player's money/balance off the sidebar scoreboard. Server-specific, so the money
 * {@link Pattern} is supplied by the caller (the Rynek module exposes it as an editable setting).
 * Everything is guarded — returns -1 when the balance can't be determined.
 */
public final class ScoreboardUtils {

    /** All visible sidebar lines (formatting stripped), top to bottom. Never null. */
    public static List<String> getSidebarLines() {
        List<String> lines = new ArrayList<>();
        try {
            if (mc.world == null) return lines;
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return lines;

            for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
                String owner = entry.owner();
                Team team = sb.getScoreHolderTeam(owner);
                String line = team != null
                        ? Team.decorateName(team, Text.literal(owner)).getString()
                        : owner;
                lines.add(Formatting.strip(line));
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    /**
     * Scans the sidebar for the first line matching {@code moneyPattern} and parses group 1 as a
     * number (thousands separators / spaces stripped). Returns -1 if nothing matches.
     */
    public static long getNumericBalance(Pattern moneyPattern) {
        List<String> lines = getSidebarLines();
        if (lines.isEmpty()) return -1;

        long found = match(lines, moneyPattern);
        if (found >= 0) return found;

        // The configured pattern is only a hint. This server writes the sidebar as "Pieniądze: $1,234",
        // which the old default (saldo / stan konta / money / balans) never matched — so the balance read
        // as "unknown" everywhere it was shown, the HUD included. Rather than make everyone re-type a
        // regex, fall back through the shapes these servers actually use.
        for (Pattern fallback : FALLBACKS) {
            found = match(lines, fallback);
            if (found >= 0) return found;
        }
        return -1;
    }

    /** Sidebar shapes seen in the wild, tried in order of how specific they are. */
    private static final Pattern[] FALLBACKS = {
            Pattern.compile("(?i)pieni[ąa]dze:?\\s*\\$?\\s*([0-9][0-9 .,]*)"),
            Pattern.compile("(?i)(?:saldo|stan konta|balans|money|kasa|coins?):?\\s*\\$?\\s*([0-9][0-9 .,]*)"),
            Pattern.compile("\\$\\s*([0-9][0-9 .,]*)")
    };

    private static long match(List<String> lines, Pattern pattern) {
        if (pattern == null) return -1;
        for (String line : lines) {
            if (line == null) continue;
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find() || matcher.groupCount() < 1) continue;
            String raw = matcher.group(1);
            if (raw == null) continue;
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
