# MeshChat Protocol Specification

Version: 0.2 — MVP routing layer  
Status: Frozen for implementation

---

## 1. Overview

MeshChat uses Bluetooth Low Energy (BLE) as its physical transport and a custom binary protocol
for packet encoding. The protocol has two layers:

- **Transport layer** — direct BLE sessions, handshake/ack, fragmentation  
- **Routing layer** — mesh packets that carry relay metadata and can traverse multiple hops

A node may be the _originator_, a _relay_, or the _destination_ of a routed message. The transport
layer is unaware of multi-hop intent; it only delivers raw byte chunks to the next BLE neighbor.
The routing layer decides what to do with a complete packet once reassembled.

---

## 2. BLE Transport

### 2.1 GATT Services

| UUID suffix | Role |
|-------------|------|
| `...def0`   | MeshChat service |
| `...def1`   | Handshake characteristic |
| `...def2`   | Message characteristic |
| `...def3`   | ACK characteristic |

### 2.2 Advertising

Peripheral advertises as `MC_<name8>` (first 8 chars of display name, prefix `MC_`).

### 2.3 Fragmentation

Payloads larger than **CHUNK_SIZE = 18 bytes** are split into fragments:

```
0xF0  fragStart    [totalLen: 2 BE] [seqTotal: 1] [data: 14 bytes max]
0xF1  fragContinue [seqNum: 1]      [data: 16 bytes max]
0xF2  fragEnd      [seqNum: 1]      [data: 16 bytes max]
```

The reassembler is keyed per `(deviceId, characteristicUUID)` to isolate concurrent streams.

---

## 3. Packet Types

All packets begin with a 1-byte type marker. The rest of the bytes follow the layout for that type.

### 3.1 Type Map

| Marker | Name            | Layer     |
|--------|-----------------|-----------|
| `0x01` | Handshake       | Transport |
| `0x02` | Message         | Transport |
| `0x03` | Ack             | Transport |
| `0x04` | Beacon          | Routing   |
| `0x05` | RoutedMessage   | Routing   |
| `0x06` | RouteAck        | Routing   |
| `0x07` | DeliveryAck     | Routing   |
| `0x08` | RouteFailure    | Routing   |
| `0xF0` | FragmentStart   | Framing   |
| `0xF1` | FragmentContinue| Framing   |
| `0xF2` | FragmentEnd     | Framing   |

### 3.2 Length Prefix Conventions

- **str1** — `[len: 1 byte][utf-8 bytes: len]` — max 255 UTF-8 bytes per field
- **str2** — `[len: 2 bytes BE][utf-8 bytes: len]` — max 65535 UTF-8 bytes, used for message body only
- **int8** — 1 unsigned byte (0–255)
- **int32 BE** — 4 bytes big-endian signed integer
- **int64 BE** — 8 bytes big-endian signed long (timestamps are Unix millis)

---

## 4. Transport Packet Layouts

### 4.1 Handshake `0x01`
```
[0x01] [displayName: str1] [deviceId: str1, max 8 chars]
```
Exchanged on first connection to establish stable peer identity.

### 4.2 Message `0x02`
```
[0x02] [id: str1, max 12 chars] [senderDeviceId: str1, max 8 chars] [text: utf-8 rest-of-packet]
```
Direct message between two BLE-adjacent nodes. No relay metadata.

### 4.3 Ack `0x03`
```
[0x03] [messageId: str1, max 12 chars]
```
Confirms receipt of a transport-layer Message.

---

## 5. Routing Packet Layouts

### 5.1 Beacon `0x04`
```
[0x04] [nodeId: str1] [displayName: str1] [timestamp: int64 BE] [seqNum: int32 BE]
```

Purpose: a node broadcasts its presence to all BLE neighbors. Neighbors propagate
the beacon so nodes that are not directly connected learn of each other's existence.

Fields:
- `nodeId` — sender's stable device ID
- `displayName` — human-readable name snapshot
- `timestamp` — Unix millis when the beacon was originated
- `seqNum` — monotonically increasing per originator; receivers discard stale beacons

### 5.2 RoutedMessage `0x05`
```
[0x05]
[packetId: str1]
[sourcePublicKey: str1]
[destinationPublicKey: str1]
[destinationDisplayNameSnapshot: str1]
[destinationGeoHint: str1]
[ttl: int8]
[hopCount: int8]
[routingMode: int8]
[timestamp: int64 BE]
[signature: str1]
[body: str2]
```

Purpose: carries a user message across one or more hops.

Fields:
- `packetId` — UUID; used for duplicate suppression (see §7)
- `sourcePublicKey` — originator's device ID
- `destinationPublicKey` — target's device ID
- `destinationDisplayNameSnapshot` — display name at send time; informational only
- `destinationGeoHint` — optional routing hint e.g. `"SF:US"`, empty string if unknown
- `ttl` — remaining hop budget; a relay decrements before forwarding; drop when < 0
- `hopCount` — number of relays traversed; incremented by each relay
- `routingMode` — `0x00` DIRECT, `0x01` BROADCAST, `0x02` GREEDY
- `timestamp` — Unix millis of origination
- `signature` — placeholder; will hold HMAC-SHA256 of `packetId + body`
- `body` — plaintext message content (str2 to support longer messages)

### 5.3 RouteAck `0x06`
```
[0x06] [packetId: str1] [hopNodeId: str1] [timestamp: int64 BE]
```

Purpose: per-hop receipt confirmation. A relay node sends this back toward the
previous hop after successfully forwarding the packet.

### 5.4 DeliveryAck `0x07`
```
[0x07] [packetId: str1] [destinationNodeId: str1] [timestamp: int64 BE]
```

Purpose: end-to-end delivery confirmation. The destination node sends this back
toward the originator after delivering the message to the application layer.

### 5.5 RouteFailure `0x08`
```
[0x08] [packetId: str1] [failingNodeId: str1] [reason: int8] [timestamp: int64 BE]
```

Purpose: propagated back toward the source when a packet cannot be forwarded.

Reason codes:
- `0x00` TTL_EXCEEDED — relay decremented TTL to 0, cannot forward
- `0x01` NO_ROUTE — no known next hop for destination
- `0x02` DESTINATION_UNREACHABLE — destination not seen in neighbor table

---

## 6. Routing Semantics

### 6.1 RoutingMode

| Value | Name      | Behavior |
|-------|-----------|----------|
| `0x00`| DIRECT    | Send only to the one neighbor directly connected at the transport layer |
| `0x01`| BROADCAST | Flood to all currently connected neighbors (except the neighbor it arrived from) |
| `0x02`| GREEDY    | Forward toward the neighbor closest to the destination in the routing table (not yet implemented) |

### 6.2 TTL and Hop Count

- Default TTL at origination: **7**
- Each relay decrements `ttl` by 1 and increments `hopCount` by 1 before forwarding
- If `ttl` drops below 0 after decrement, the packet is silently dropped and a
  RouteFailure(TTL_EXCEEDED) MAY be sent back

### 6.3 Routing Table

Not yet implemented. Currently all routing uses BROADCAST mode. A future version will
build a routing table from Beacon observations, recording `(nodeId → bestNeighbor, rssi, seenAt)`.

---

## 7. Duplicate Suppression

Every node maintains a `PacketIdCache` — an LRU map of recently seen `packetId` values:

- **Capacity**: 1000 entries (LRU eviction)
- **TTL**: 5 minutes (entries older than this are evicted on access)

On receiving any RoutedMessage:
1. Check `PacketIdCache.isSeen(packet.packetId)`
2. If already seen → **drop silently**
3. Otherwise → `markSeen`, then route or deliver

The originator also calls `markSeen` for self-originated packets to prevent loopback.

---

## 8. Delivery Flow

```
Originator                  Relay A                  Destination
    |                          |                          |
    |-- RoutedMessage -------->|                          |
    |                          |-- RoutedMessage -------->|
    |                          |<-- RouteAck -------------|  (hop ack)
    |<-- RouteAck --------------|                          |
    |                          |<-- DeliveryAck -----------|
    |<-- DeliveryAck -----------|                          |
```

RouteAck and DeliveryAck propagation is best-effort. The originator retransmit
strategy is not yet specified.

---

## 9. Assumptions and Future Work

- **Signatures**: The `signature` field is present but empty in this MVP. A future version
  will populate it with HMAC-SHA256(`packetId || body`, senderPrivateKey).
- **Encryption**: Message bodies are currently plaintext. End-to-end encryption using the
  `destinationPublicKey` is deferred.
- **GeoHint routing**: The `destinationGeoHint` field is reserved; greedy geo-routing is not
  yet implemented.
- **Beacon propagation**: Beacons are currently only recorded by direct neighbors. Multi-hop
  beacon propagation requires the Beacon to carry its own TTL (future field).
- **Conversation binding**: The routing layer is decoupled from the conversation data model.
  Delivery calls back into `ConversationRepository` via the `deliverLocally` callback injected
  into `MeshRouterImpl`.
