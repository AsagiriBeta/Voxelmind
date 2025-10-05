package asagiribeta.voxelmind.client.util;

import net.minecraft.util.StringUtil;

/**
 * Sanitizes outbound AI chat messages so they never trigger the server's
 * illegal chat character disconnect. Removes any character not allowed by
 * {@link StringUtil#isAllowedChatCharacter(char)} and normalizes newlines
 * and other whitespace into single spaces. Also enforces a max length.
 */
public final class ChatSanitizer {
    private static final int MAX_LEN = 256; // vanilla hard cap is 256

    private ChatSanitizer() {}

    public static Result sanitize(String in) {
        if (in == null) return new Result("", false, 0);
        String trimmed = in.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean changed = false;
        int removed = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Normalize common newline / control whitespace to space
            if (c == '\n' || c == '\r' || c == '\t') {
                if (out.length() > 0 && out.charAt(out.length()-1) == ' ') {
                    // skip duplicate space
                } else {
                    out.append(' ');
                }
                if (c != ' ') { changed = true; removed++; }
                continue;
            }
            if (!StringUtil.isAllowedChatCharacter(c)) {
                changed = true; removed++;
                continue; // drop
            }
            out.append(c);
            if (out.length() >= MAX_LEN) { // truncate
                if (i + 1 < trimmed.length()) changed = true;
                break;
            }
        }
        // Collapse multiple spaces
        String collapsed = out.toString().replaceAll(" +", " ").trim();
        if (!collapsed.equals(trimmed)) changed = true;
        return new Result(collapsed, changed, removed);
    }

    public record Result(String sanitized, boolean changed, int removedCount) {}
}

