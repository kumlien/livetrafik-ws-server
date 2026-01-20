import ws from 'k6/ws';
import { check, sleep } from 'k6';

// Konfigureras via env-variabler
const WS_URL = __ENV.WS_URL || 'wss://localhost:8080/ws';
const VUS = Number(__ENV.VUS || '10');        // antal samtidiga klienter
const DURATION = __ENV.DURATION || '1m';      // total testlängd
const MAX_MESSAGES = Number(__ENV.MAX_MESSAGES || '0'); // 0 = obegränsat

export const options = {
  vus: VUS,
  duration: DURATION,
};

export default function () {
  const userId = `user_${__VU}`;

  const res = ws.connect(WS_URL, { tags: { userId } }, function (socket) {
    let msgCount = 0;

    socket.on('open', () => {
      console.log(`VU ${__VU}: connected as ${userId}`);
      // Ingen send() här – klienten lyssnar bara
    });

    socket.on('message', (data) => {
      msgCount += 1;
      // Här kan du lägga ev. assertion/parsing
      // console.log(`VU ${__VU} received: ${data}`);

      if (MAX_MESSAGES > 0 && msgCount >= MAX_MESSAGES) {
        console.log(`VU ${__VU}: reached MAX_MESSAGES=${MAX_MESSAGES}, closing`);
        socket.close();
      }
    });

    socket.on('error', (e) => {
      console.error(`VU ${__VU}: WebSocket error: ${e.error()}`);
    });

    socket.on('close', () => {
      console.log(`VU ${__VU}: disconnected, received ${msgCount} messages`);
    });

    // Håll anslutningen öppen tills iterationen är klar
    // Om du vill tvinga en maxlivslängd per klient kan du använda setTimeout:
    // socket.setTimeout(() => {
    //   console.log(`VU ${__VU}: timeout reached, closing socket`);
    //   socket.close();
    // }, 60 * 1000); // t.ex. 60 sek
  });

  check(res, {
    'connected (status 101)': (r) => r && r.status === 101,
  });

  // Minimal sleep, har mest betydelse vid andra executors
  sleep(1);
}
