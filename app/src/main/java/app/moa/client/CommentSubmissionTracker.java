package app.moa.client;

/** Correlates a trusted comment submission with one confirmed public comment number. */
final class CommentSubmissionTracker {
    private String page = "", ticket = "";
    private long started;
    boolean begin(String url, String value, long now) {
        cancel();
        String canonical = CommunityStore.canonicalArticleUrl(url);
        if (canonical == null || value == null || !value.matches("[a-f0-9]{32}") || now < 0) return false;
        page = canonical; ticket = value; started = now; return true;
    }
    String complete(String url, String value, String comment, long now) {
        if (ticket.isEmpty()) return null;
        if (now < started || now - started >= 120_000 || !page.equals(CommunityStore.canonicalArticleUrl(url))) { cancel(); return null; }
        if (!ticket.equals(value)) return null;
        String result = comment != null && comment.matches("[1-9][0-9]{0,18}") ? page + "?comment=" + comment : null;
        cancel(); return result;
    }
    void cancel() { page = ""; ticket = ""; started = 0; }
    boolean fail(String url, String value, long now) {
        if (ticket.isEmpty() || !ticket.equals(value)) return false;
        boolean current = now >= started && now - started < 120_000 && page.equals(CommunityStore.canonicalArticleUrl(url));
        cancel(); return current;
    }
}
