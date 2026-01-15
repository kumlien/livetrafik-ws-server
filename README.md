[![CI Build & Test](https://github.com/kumlien/livetrafik-ws-server/actions/workflows/ci.yml/badge.svg)](https://github.com/kumlien/livetrafik-ws-server/actions/workflows/ci.yml)  
[![Native Image Build (ARM64)](https://github.com/kumlien/livetrafik-ws-server/actions/workflows/native-arm64.yml/badge.svg)](https://github.com/kumlien/livetrafik-ws-server/actions/workflows/native-arm64.yml)

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

- Prenumererar på Supabase Realtime (slash-baserade kanaler `region/vehicles/{type}`)
- Remove-first cache med delta-merge (stödjer `removed_vehicle_ids`)
- Exponerar STOMP/SockJS WebSocket på `/ws`
- REST API: `/api/health`, `/api/latest/{region}`
- Automatisk återanslutning + strukturerade loggar per region/typ
- In-memory cache för senaste fordonsdata med stale-cleanup fallback

## Krav

- Java 25 (Temurin eller GraalVM)
- Maven 3.9+
- Raspberry Pi 5 (eller annan Linux-maskin)

## Konfiguration

### application.yml

~~~~yaml
spring:
  threads:
    virtual:
      enabled: true

server:
  port: 9001

supabase:
  url: https://your-project.supabase.co
  anon-key: ${SUPABASE_ANON_KEY}
  regions: ul,sl
  vehicle-types: bus,train
  # (valfritt) channel: vehicle-updates-ul-bus,vehicle-updates-ul-train

logging:
  level:
    se.trafiklive: DEBUG
    org.springframework.web.socket: INFO
~~~~

> **Bakom kulisserna:** `spring.threads.virtual.enabled=true` gör att Spring Boot använder Virtual Threads för inkommande HTTP/WebSocket-förfrågningar, vilket minskar trådtrycket på Pi:n.

### Miljövariabler

~~~~bash
export SUPABASE_ANON_KEY="eyJhbGciOiJIUzI1NiIs..."
export SUPABASE_REGIONS="ul,sl"
export SUPABASE_VEHICLE_TYPES="bus,train"
# valfritt: overridea kanaler helt
# export SUPABASE_CHANNEL="vehicle-updates-vt-bus,vehicle-updates-vt-train"
~~~~

## Vehicle delta handling (remove-first)

Supabase Edge Functions skickar deltapayloads per kanal:

~~~~json
{
  "vehicles": [ /* delta-updates */ ],
  "removed_vehicle_ids": ["vehicle_1", "vehicle_2"],
  "region": "ul",
  "vehicleType": "bus",
  "timestamp": 1730000000000
}
~~~~

- `vehicles` och `removed_vehicle_ids` är valfria. Meddelanden kan innehålla endast borttagningar eller endast uppdateringar.
- Java-servern parsar payloaden till `VehicleBroadcastPayload` och kör **remove-first, merge-second** innan stale-cleanup.
- Cache-metrik loggas per region/typ:

~~~~text
[STOMP] vehicles update: region=ul type=bus received=45 removed=3 cacheSize=512
~~~~

Checklistan för att verifiera i produktion:

1. Loggar ovan ska visas för varje aktiv kanal.
2. `removed` bör vara >0 när `removed_vehicle_ids` skickas.
3. `cacheSize` ska minska när fordon tas bort och öka när nya anländer.

> Alla detaljer och edge cases finns i `.windsurf/rules/java-vehicle-broadcast.md`.

## Bygg

~~~~bash
git clone https://github.com/kumlien/livetrafik-ws-server.git
cd livetrafik-ws-server
./mvnw clean package -DskipTests
~~~~

JAR-filen hamnar i `target/livetrafik-ws-server-0.0.1-SNAPSHOT.jar` (eller motsvarande version enligt `pom.xml`).

## Kör lokalt

~~~~bash
java -jar target/livetrafik-ws-server-0.0.1-SNAPSHOT.jar
~~~~

Testa:

~~~~bash
curl http://localhost:9001/api/health
curl http://localhost:9001/api/latest/ul
~~~~

## Deployment på Raspberry Pi

### Native ARM64-byggen från GitHub Actions

1. **Trigga workflowet**  
   Kör Actions-jobbet **“Native Image Build (ARM64)”** (antingen från UI eller via `gh workflow run native-arm64.yml`). Sätt `create_release=true` om du vill få en release med binären bifogad.

2. **Hämta binären**  
   - **Från release:**  

     ~~~~bash
     VERSION=v1.0.0
     wget https://github.com/svante/livetrafik-ws-server/releases/download/$VERSION/trafik-websocket-server-linux-arm64 \
       -O trafik-websocket-server-linux-arm64
     chmod +x trafik-websocket-server-linux-arm64
     ~~~~
   - **Från workflow-artifact (utan release):**  

     ~~~~bash
     gh run download <run-id> \
       --repo kumlien/livetrafik-ws-server \
       -n trafik-websocket-server-arm64 \
       -D target
     chmod +x target/trafik-websocket-server-linux-arm64
     ~~~~
     (kräver `gh auth login` och val av ARM64-run)

3. **Placera binären på Pi**  

   ~~~~bash
   scp trafik-websocket-server-linux-arm64 pi@raspberrypi.local:/home/pi/bin/
   ssh pi@raspberrypi.local chmod +x /home/pi/bin/trafik-websocket-server-linux-arm64
   ~~~~

4. **systemd-tjänst för native-binär**

   ~~~~ini
   [Unit]
   Description=Trafik Live Native Server
   After=network.target

   [Service]
   Type=simple
   User=pi
   WorkingDirectory=/home/pi
   ExecStart=/home/pi/bin/trafik-websocket-server-linux-arm64
   Environment=SUPABASE_ANON_KEY=your-anon-key-here
   Restart=always
   RestartSec=5
   NoNewPrivileges=true
   ProtectSystem=strict
   ProtectHome=read-only
   ReadWritePaths=/home/pi/logs

   [Install]
   WantedBy=multi-user.target
   ~~~~

   Skapa filen direkt på Pi:

   ~~~~bash
   sudo tee /etc/systemd/system/trafik-ws.service > /dev/null <<'EOF'
   [Unit]
   Description=Trafik Live Native Server
   After=network.target

   [Service]
   Type=simple
   User=pi
   WorkingDirectory=/home/pi
   ExecStart=/home/pi/bin/trafik-websocket-server-linux-arm64
   Environment=SUPABASE_ANON_KEY=your-anon-key-here
   Restart=always
   RestartSec=5
   NoNewPrivileges=true
   ProtectSystem=strict
   ProtectHome=read-only
   ReadWritePaths=/home/pi/logs

   [Install]
   WantedBy=multi-user.target
   EOF
   ~~~~

5. **Hantera tjänsten**

   ~~~~bash
   sudo systemctl daemon-reload
   sudo systemctl enable trafik-ws
   sudo systemctl restart trafik-ws     # ny binär
   sudo systemctl status trafik-ws
   journalctl -fu trafik-ws
   ~~~~

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
- **Topics:**
  - `/topic/{region}/vehicles/bus` – endast bussar för regionen
  - `/topic/{region}/vehicles/train` – endast tåg för regionen
  - `/topic/vehicles-{region}` (legacy) – alla fordon per region
  - `/topic/vehicles` (deprecated) – global feed av alla fordon

Klientexempel (JavaScript):

~~~~javascript
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const client = new Client({
  webSocketFactory: () => new SockJS('http://pi.local:9001/ws'),
  onConnect: () => {
    client.subscribe('/topic/ul/vehicles/bus', (message) => {
      const data = JSON.parse(message.body);
      console.log('Bussar:', data.vehicles);
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
