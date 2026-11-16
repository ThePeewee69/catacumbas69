package com.catacumbas69.catacommons.audit;

/**
 * Who made a request, as recorded in the {@code actor} of audit events (EVT-3). Catapulsar knows the user, who
 * signs in through Dex, and names them in {@value #HEADER} on every call it makes to another app (orch.md decision
 * 100); without that header the actor is the first {@code X-Forwarded-For} address, else the caller's own address.
 * The other apps' Routes have no login, so the header is trusted as it comes (decision 100).
 */
public final class Actor {

    /** Header with the signed-in user that Catapulsar acted for. */
    public static final String HEADER = "X-Catacumbas69-User";

    private Actor() {
    }

    /**
     * Resolves the actor of a request from its headers.
     *
     * @param user value of {@value #HEADER}, or {@code null}
     * @param forwardedFor value of {@code X-Forwarded-For}, or {@code null}
     * @param remoteAddress the address the request came from
     * @return the user if named, else the first forwarded address, else the remote address
     */
    public static String resolve(String user, String forwardedFor, String remoteAddress) {
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return remoteAddress;
    }
}
