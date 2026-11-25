package com.catacumbas69.catasonarr.pages;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The open pages, through a WebSocket at {@code /api/v1/updates} (orch.md decision 118): each gets every change of
 * this replica's pages subscription, and a ping now and then so the router does not close a quiet connection. The
 * page only listens; what it sends is ignored.
 */
public class PageUpdates extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PageUpdates.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.removeIf(s -> s.getId().equals(session.getId()));
    }

    /**
     * Sends a change to every open page; a page that cannot take it is dropped and reconnects by itself.
     *
     * @param json the change, as the page reads it
     */
    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        sessions.forEach(session -> send(session, () -> session.sendMessage(message)));
    }

    /** Pings every open page, so quiet connections stay open through the router. */
    public void ping() {
        PingMessage ping = new PingMessage();
        sessions.forEach(session -> send(session, () -> session.sendMessage(ping)));
    }

    /**
     * Returns how many pages are open on this replica.
     *
     * @return the number of WebSocket sessions
     */
    public int open() {
        return sessions.size();
    }

    private void send(WebSocketSession session, Sender sender) {
        try {
            if (session.isOpen()) {
                sender.send();
                return;
            }
        } catch (IOException | IllegalStateException e) {
            LOG.debug("Page {} dropped: {}", session.getId(), e.getMessage());
        }
        sessions.remove(session);
    }

    @FunctionalInterface
    private interface Sender {
        void send() throws IOException;
    }
}
