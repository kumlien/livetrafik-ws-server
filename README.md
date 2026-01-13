# Trafik Live – Java WebSocket Server

Lokal WebSocket-relay för realtidsdata från Supabase till webbklienter via STOMP/SockJS.

## Arkitektur

~~~~
┌─────────────────────┐
│  Supabase Realtime  │
│  (vehicle-updates)  │
└──────────┬──────────┘
           │ WebSocket
           ▼
┌─────────────────────┐
│   Raspberry Pi 5    │
│  ┌───────────────┐  │
│  │  Spring Boot  │  │
│  │  WebSocket    │  │
│  │  Server       │  │
│  └───────────────┘  │
│         │           │
│    Port 9001        │
└─────────┬───────────┘
          │
          ▼ (valfritt)
┌─────────────────────┐
│  Cloudflare Tunnel  │
│  ws.example.com     │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│    Webbläsare       │
│  (SockJS/STOMP)     │
└─────────────────────┘
~~~~

## Funktioner

- Prenumererar på Supabase Realtime (`vehicle-updates` channel)
- Exponerar STOMP/SockJS WebSocket på `/ws`
- REST API: `/api/health`, `/api/latest/{region}`
- Automatisk återanslutning vid avbrott
- In-memory cache för senaste fordonsdata

## Krav

- Java 17+
- Maven 3.8+
- Raspberry Pi 5 (eller annan Linux-maskin)

## Konfiguration

### application.yml

~~~~yaml
server:
  port: 9001

supabase:
  url: https://your-project.supabase.co
  anon-key: ${SUPABASE_ANON_KEY}
  channel: vehicle-updates

logging:
  level:
    se.trafiklive: DEBUG
    org.springframework.web.socket: INFO
~~~~

### Miljövariabler

~~~~bash
export SUPABASE_ANON_KEY="eyJhbGciOiJIUzI1NiIs..."
~~~~

## Bygg

~~~~bash
git clone https://github.com/your-org/trafik-ws-server.git
cd trafik-ws-server
mvn clean package -DskipTests
~~~~

JAR-filen hamnar i `target/trafik-ws-server-1.0.0.jar`.

## Kör lokalt

~~~~bash
java -jar target/trafik-ws-server-1.0.0.jar
~~~~

Testa:

~~~~bash
curl http://localhost:9001/api/health
curl http://localhost:9001/api/latest/ul
~~~~

## Deployment på Raspberry Pi

### 1. Installera Java

~~~~bash
sudo apt update
sudo apt install openjdk-17-jre-headless -y
java -version
~~~~

### 2. Kopiera JAR-fil

~~~~bash
scp target/trafik-ws-server-1.0.0.jar pi@raspberrypi.local:~/
~~~~

### 3. Skapa systemd-tjänst

~~~~bash
sudo nano /etc/systemd/system/trafik-ws.service
~~~~

Innehåll:

~~~~ini
[Unit]
Description=Trafik Live WebSocket Server
After=network.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi
ExecStart=/usr/bin/java -Xmx256m -jar /home/pi/trafik-ws-server-1.0.0.jar
Restart=always
RestartSec=10
Environment=SUPABASE_ANON_KEY=your-anon-key-here

# Säkerhet
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/home/pi/logs

[Install]
WantedBy=multi-user.target
~~~~

### 4. Aktivera och starta

~~~~bash
sudo systemctl daemon-reload
sudo systemctl enable trafik-ws
sudo systemctl start trafik-ws
sudo systemctl status trafik-ws
~~~~

### 5. Visa loggar

~~~~bash
journalctl -u trafik-ws -f
~~~~

## Cloudflare Tunnel (valfritt)

Om du vill exponera servern externt utan att öppna portar i routern.

### Quick Tunnel (temporär)

~~~~bash
sudo apt install cloudflared
cloudflared tunnel --url http://localhost:9001
~~~~

### Permanent Tunnel

~~~~bash
cloudflared tunnel login
cloudflared tunnel create trafik-ws
~~~~

Skapa `~/.cloudflared/config.yml`:

~~~~yaml
tunnel: <tunnel-id>
credentials-file: /home/pi/.cloudflared/<tunnel-id>.json

ingress:
  - hostname: ws.example.com
    service: http://localhost:9001
  - service: http_status:404
~~~~

Kör som tjänst:

~~~~bash
sudo cloudflared service install
sudo systemctl start cloudflared
~~~~

## API-referens

### WebSocket

- **Endpoint:** `/ws`
- **Protokoll:** STOMP över SockJS
- **Topic:** `/topic/vehicles-{region}`

Klientexempel (JavaScript):

~~~~javascript
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const client = new Client({
  webSocketFactory: () => new SockJS('http://pi.local:9001/ws'),
  onConnect: () => {
    client.subscribe('/topic/vehicles-ul', (message) => {
      const data = JSON.parse(message.body);
      console.log('Vehicles:', data.vehicles);
    });
  },
});

client.activate();
~~~~

### REST API

| Endpoint | Metod | Beskrivning |
|----------|-------|-------------|
| `/api/health` | GET | Hälsokontroll |
| `/api/latest/{region}` | GET | Senaste fordonsdata för region |

### Payload-format

~~~~json
{
  "vehicles": [
    {
      "vehicle_id": "9031005901234567",
      "latitude": 59.8586,
      "longitude": 17.6389,
      "bearing": 45.0,
      "line_number": "8",
      "destination": "Uppsala C",
      "delay_seconds": 120,
      "vehicle_type": "bus",
      "region": "ul"
    }
  ],
  "region": "ul",
  "timestamp": 1705012345678
}
~~~~

## Felsökning

| Problem | Lösning |
|---------|---------|
| Servern startar inte | Kontrollera `journalctl -u trafik-ws -n 50` |
| Ingen data från Supabase | Verifiera `SUPABASE_ANON_KEY` och channel-namn |
| WebSocket-anslutning misslyckas | Kontrollera brandvägg och CORS-inställningar |
| Hög minnesanvändning | Justera `-Xmx` i systemd-filen |

## Licens

MIT License

## Kontakt

- **Projekt:** https://trafiklive.se
- **Issues:** GitHub Issues
