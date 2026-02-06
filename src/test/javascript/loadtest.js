#!/usr/bin/env node
import { Command } from 'commander';
import chalk from 'chalk';
import SockJS from 'sockjs-client/dist/sockjs.js';
import { Client as StompClient } from '@stomp/stompjs';
import WebSocket from 'ws';

// SockJS + STOMP expect browser globals – stub the essentials for Node
if (!globalThis.window) {
  globalThis.window = globalThis;
}
if (!globalThis.document) {
  globalThis.document = { createElement: () => ({}), cookie: '' };
}
if (!globalThis.WebSocket) {
  globalThis.WebSocket = WebSocket;
}

const program = new Command();

program
  .name('sockjs-loadtest')
  .description('SockJS/STOMP load tester for livetrafik WebSocket server')
  .option('-u, --url <url>', 'SockJS endpoint URL', 'https://localhost:9001/ws')
  .option('-c, --clients <number>', 'number of concurrent clients', parseIntValue, 10)
  .option('-d, --duration <duration>', 'test duration (e.g. 30s, 2m, 1h)', '1m')
  .option('-r, --regions <list>', 'comma separated regions', 'ul,sl')
  .option('-t, --vehicle-types <list>', 'comma separated vehicle types', 'bus,train')
  .option('-m, --max-messages <number>', 'max messages per client before disconnect (0 = unlimited)', parseIntValue, 0)
  .option('--log-every <number>', 'log progress every N messages per client', parseIntValue, 25)
  .option('--latency-samples <number>', 'number of latency samples kept for percentile calculation', parseIntValue, 10000)
  .parse(process.argv);

const options = program.opts();
const testDurationMs = parseDuration(options.duration);
const regions = csvToList(options.regions);
const vehicleTypes = csvToList(options.vehicleTypes);

const metrics = {
  startTime: Date.now(),
  clients: options.clients,
  targetUrl: options.url,
  connectedClients: 0,
  failedClients: 0,
  messagesReceived: 0,
  errors: 0,
  latency: {
    count: 0,
    sum: 0,
    min: Number.POSITIVE_INFINITY,
    max: Number.NEGATIVE_INFINITY,
    samples: [],
  },
};

const latencySampleLimit = options.latencySamples;

async function main() {
  console.log(chalk.cyan(`\nStarting SockJS load test against ${options.url}`));
  console.log(chalk.cyan(`Clients: ${options.clients}, Duration: ${options.duration}, Regions: ${regions.join(', ')}, Types: ${vehicleTypes.join(', ')}`));

  const clientPromises = [];
  for (let i = 0; i < options.clients; i++) {
    clientPromises.push(runClient(i + 1));
  }

  // Wait for all clients to finish
  await Promise.allSettled(clientPromises);
  printSummary();
}

function runClient(clientNumber) {
  return new Promise((resolve) => {
    const clientId = `sockjs-client-${clientNumber}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const stompClient = new StompClient({
      reconnectDelay: 0,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      connectHeaders: {
        'accept-version': '1.1,1.0',
        'client-id': clientId,
      },
      webSocketFactory: () => new SockJS(options.url, null, {
        transports: ['websocket', 'xhr-streaming', 'xhr-polling'],
        headers: { 'User-Agent': 'sockjs-loadtest' },
      }),
      debug: () => {},
    });

    let messageCount = 0;
    let resolved = false;

    const finish = () => {
      if (resolved) {
        return;
      }
      resolved = true;
      stompClient.deactivate();
      resolve({ messageCount });
    };

    stompClient.onConnect = () => {
      metrics.connectedClients += 1;
      subscribeToTopics(stompClient, clientId, (payload) => {
        metrics.messagesReceived += 1;
        messageCount += 1;
        recordLatency(payload);
        const maxMessages = options.maxMessages;
        if (maxMessages > 0 && messageCount >= maxMessages) {
          console.log(chalk.yellow(`[${clientId}] reached max messages (${messageCount}), disconnecting`));
          finish();
        } else if (options.logEvery > 0 && messageCount % options.logEvery === 0) {
          console.log(chalk.gray(`[${clientId}] messages=${messageCount}`));
        }
      });
    };

    stompClient.onStompError = (frame) => {
      metrics.errors += 1;
      console.error(chalk.red(`[${clientId}] STOMP error: ${frame.body}`));
      finish();
    };

    stompClient.onWebSocketError = (event) => {
      metrics.errors += 1;
      console.error(chalk.red(`[${clientId}] WebSocket error: ${event.message || event}`));
      finish();
    };

    stompClient.onDisconnect = () => {
      finish();
    };

    stompClient.activate();

    // Force stop after duration
    setTimeout(() => {
      finish();
    }, testDurationMs);
  });
}

function subscribeToTopics(stompClient, clientId, onMessage) {
  regions.forEach((region) => {
    vehicleTypes.forEach((vehicleType) => {
      const destination = `/topic/${region}/vehicles/${vehicleType}`;
      stompClient.subscribe(destination, (message) => {
        try {
          const payload = JSON.parse(message.body);
          onMessage(payload);
        } catch (err) {
          metrics.errors += 1;
          console.error(chalk.red(`[${clientId}] Failed to parse payload from ${destination}: ${err.message}`));
        }
      });
    });
  });
}

function printSummary() {
  const durationSec = ((Date.now() - metrics.startTime) / 1000).toFixed(1);
  console.log('\n');
  console.log(chalk.green('Load test complete'));
  console.log(chalk.green('===================='));
  console.log(`Target URL:      ${metrics.targetUrl}`);
  console.log(`Clients:         ${metrics.clients}`);
  console.log(`Connected:       ${metrics.connectedClients}`);
  console.log(`Messages recv:   ${metrics.messagesReceived}`);
  console.log(`Errors:          ${metrics.errors}`);
  if (metrics.latency.count > 0) {
    const avg = metrics.latency.sum / metrics.latency.count;
    const percentiles = calculateLatencyPercentiles();
    console.log(`Latency avg:     ${avg.toFixed(1)} ms`);
    console.log(
      `Latency p50/p90/p99: ${percentiles.p50.toFixed(1)} / ${percentiles.p90.toFixed(1)} / ${percentiles.p99.toFixed(1)} ms`
    );
    console.log(`Latency min/max: ${metrics.latency.min.toFixed(1)} / ${metrics.latency.max.toFixed(1)} ms`);
  } else {
    console.log('Latency:         n/a (no timestamp field in payloads)');
  }
  console.log(`Duration (wall): ${durationSec}s`);
}

function recordLatency(payload) {
  const serverTimestamp = extractTimestamp(payload);
  if (!serverTimestamp) {
    return;
  }
  const now = Date.now();
  const latency = now - serverTimestamp;
  if (latency < 0 || Number.isNaN(latency)) {
    return;
  }
  const stats = metrics.latency;
  stats.count += 1;
  stats.sum += latency;
  stats.min = Math.min(stats.min, latency);
  stats.max = Math.max(stats.max, latency);

  if (stats.samples.length < latencySampleLimit) {
    stats.samples.push(latency);
  } else {
    // Reservoir sampling so we still get a representative percentile set
    const replaceIndex = Math.floor(Math.random() * stats.count);
    if (replaceIndex < latencySampleLimit) {
      stats.samples[replaceIndex] = latency;
    }
  }
}

function extractTimestamp(payload) {
  if (!payload) {
    return null;
  }

  const timestampCandidate =
    payload.timestamp || payload.generatedAt || payload.time || payload.meta?.timestamp;

  if (timestampCandidate === undefined || timestampCandidate === null) {
    return null;
  }

  if (typeof timestampCandidate === 'number') {
    return timestampCandidate;
  }

  if (typeof timestampCandidate === 'string') {
    const parsed = Date.parse(timestampCandidate);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }

    const numeric = Number(timestampCandidate);
    if (!Number.isNaN(numeric) && numeric > 0) {
      return numeric;
    }
  }

  return null;
}

function calculateLatencyPercentiles() {
  const samples = metrics.latency.samples.slice().sort((a, b) => a - b);
  return {
    p50: pickPercentile(samples, 0.5),
    p90: pickPercentile(samples, 0.9),
    p99: pickPercentile(samples, 0.99),
  };
}

function pickPercentile(sortedValues, percentile) {
  if (sortedValues.length === 0) {
    return 0;
  }
  const index = Math.min(
    sortedValues.length - 1,
    Math.max(0, Math.round(percentile * (sortedValues.length - 1)))
  );
  return sortedValues[index];
}

function parseDuration(value) {
  const match = /^([0-9]+)(ms|s|m|h)$/.exec(value.trim());
  if (!match) {
    throw new Error(`Invalid duration: ${value}`);
  }
  const amount = Number(match[1]);
  const unit = match[2];
  switch (unit) {
    case 'ms':
      return amount;
    case 's':
      return amount * 1000;
    case 'm':
      return amount * 60 * 1000;
    case 'h':
      return amount * 60 * 60 * 1000;
    default:
      throw new Error(`Unsupported duration unit: ${unit}`);
  }
}

function csvToList(value) {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

function parseIntValue(value) {
  const parsed = Number.parseInt(value, 10);
  if (Number.isNaN(parsed) || parsed < 0) {
    throw new Error(`Expected positive integer, got ${value}`);
  }
  return parsed;
}

main().catch((err) => {
  console.error(chalk.red(`Fatal error: ${err.message}`));
  process.exit(1);
});
