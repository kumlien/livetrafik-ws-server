package se.kumliens.livetrafik.monitoring;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketConnectionTracker {

    private final AtomicInteger connectedClients = new AtomicInteger();
    private static final Logger log = LoggerFactory.getLogger(WebSocketConnectionTracker.class);

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        int count = connectedClients.incrementAndGet();
        log.debug("Client connected. Total clients: {}", count);
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        int count = connectedClients.updateAndGet(current -> Math.max(0, current - 1));
        log.debug("Client disconnected. Total clients: {}", count);
    }

    public int getConnectedClients() {
        return connectedClients.get();
    }
}
