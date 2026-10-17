package se.kumliens.livetrafik;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Talks real STOMP to the running app on `/ws-native`. Supabase and the heartbeat
 * endpoint point at a closed local port, so nothing leaves the machine.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "supabase.url=ws://localhost:1",
        "supabase.anon-key=test",
        "monitoring.heartbeat.url=http://localhost:1"
    })
class StompEndpointIntegrationTest {

    private static final String BUS_TOPIC = "/topic/ul/vehicles/bus";

    @LocalServerPort
    private int port;

    private final ThreadPoolTaskScheduler clientScheduler = new ThreadPoolTaskScheduler();
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        clientScheduler.initialize();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
        stompClient.setTaskScheduler(clientScheduler);
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
        clientScheduler.shutdown();
    }

    private StompSession connect(CompletableFuture<StompHeaders> connectedHeaders) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.setHeartbeat(new long[] {10_000, 10_000}); // what Bussig's usePiLiveVehicles requests
        return stompClient.connectAsync("ws://localhost:" + port + "/ws-native", new WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders headers) {
                        connectedHeaders.complete(headers);
                    }
                })
            .get(5, TimeUnit.SECONDS);
    }

    @Test
    void brokerNegotiatesHeartbeatsSoDeadConnectionsAreDetected() throws Exception {
        CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
        connect(connected);

        assertThat(connected.get(5, TimeUnit.SECONDS).getHeartbeat()).containsExactly(10_000, 10_000);
    }

    @Test
    void clientSendIsNotRelayedSoNobodyCanInjectFakeVehicles() throws Exception {
        StompSession subscriber = connect(new CompletableFuture<>());
        CompletableFuture<String> received = new CompletableFuture<>();
        subscriber.subscribe(BUS_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((String) payload);
            }
        });

        StompSession attacker = connect(new CompletableFuture<>());
        Thread.sleep(200); // let the SUBSCRIBE register before sending
        attacker.send(BUS_TOPIC, "{\"vehicles\":[{\"vehicle_id\":\"FAKE\"}]}");

        assertThat(received)
            .failsWithin(1, TimeUnit.SECONDS)
            .withThrowableOfType(TimeoutException.class);
    }
}
