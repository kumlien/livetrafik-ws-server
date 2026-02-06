# SockJS WebSocket Lasttest

Denna katalog innehåller ett Node.js-baserat lasttest som simulerar riktiga webbläsarklienter via SockJS + STOMP. Testet passar både lokala körningar (`http://localhost:9001/ws`) och Cloudflare-exponerade endpoints (`https://trafik-ws.…/ws`).

## Förutsättningar

1. **Installera Node-beroenden**:
   ```bash
   cd src/test/javascript
   npm install
   ```

2. **Starta WebSocket-servern**:
   ```bash
   # Se till att servern körs på localhost:9001
   cd /path/to/livetrafik-ws-server
   ./mvnw spring-boot:run
   # eller kör JAR-filen direkt
   java -jar target/livetrafik-ws-server-0.0.1-SNAPSHOT.jar
   ```

## Kör testet

```bash
# Default (10 klienter, 1 minut)
node loadtest.js --url=https://trafik-ws.example/ws --clients=10 --duration=1m

# Större last
node loadtest.js \
  --url=https://trafik-ws.example/ws \
  --clients=200 \
  --duration=5m \
  --regions=ul,sl \
  --vehicle-types=bus,train \
  --max-messages=0
```

| Flag | Default | Beskrivning |
|------|---------|-------------|
| `--url` | `https://localhost:9001/ws` | SockJS-endpoint (HTTP/HTTPS) |
| `--clients` | 10 | Antal parallella klienter |
| `--duration` | 1m | Testlängd per klient (stöd för ms/s/m/h) |
| `--regions` | ul,sl | Regioner att prenumerera på |
| `--vehicle-types` | bus,train | Fordonstyper |
| `--max-messages` | 0 | Avsluta klient efter N meddelanden (0 = obegränsat) |
| `--latency-samples` | 10000 | Antal mätpunkter som sparas för percentiler |

Metoden använder `sockjs-client` + `@stomp/stompjs` i Node med riktiga SockJS-transporter (websocket + XHR-fallback) och loggar mottagna meddelanden. Om payloaden innehåller ett tidsfält (`timestamp`, `generatedAt`, `time` eller `meta.timestamp`) räknas även end-to-end-latens, inklusive p50/p90/p99-percentiler.

## Felsökning

### "Connection refused"
- Kontrollera att servern körs på port 9001
- För lokal körning: använd `http://<pi-ip>:9001/ws`
- För Cloudflare: använd `https://<tunnel-host>/ws`

### "Connected: 0" i loadtestet
- Säkerställ att URL:en matchar endpointen (SockJS kräver http/https, inte ws://)
- Kontrollera att tunneln/proxy accepterar WebSocket-upgrades

### Inga meddelanden tas emot
- Verifiera i serverloggarna att Supabase levererar data
- Kontrollera att flaggorna `--regions`/`--vehicle-types` matchar topic-namn
