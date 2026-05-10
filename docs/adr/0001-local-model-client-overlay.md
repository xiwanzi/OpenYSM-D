# ADR 0001: Local Models as a Client Overlay

## Status

Accepted.

## Context

OpenYSM mirrors YSM's server-authoritative model flow: the server owns the model list, validates selections, distributes model payloads, and broadcasts each player's selected model and texture. The desired feature is for a player to use a model that exists only in their local `config/yes_steve_model/custom` directory while remaining compatible with existing YSM/OpenYSM servers.

Changing the wire protocol would make the feature easier to represent, but it would break compatibility with servers that do not know about local models.

## Decision

Implement local models as a client-only overlay:

- Load folder, `.zip`, and `.ysm` models from `config/yes_steve_model/custom`.
- Insert them into the client model table with `local/` ids and a synthetic "Local Models" root folder.
- Apply a selected local model only to the local player's client-side `PlayerCapability`.
- Persist local selections by server key and player UUID.
- Never send `local/` model ids or local-model animation/MoLang/feedback state to the server.
- Best-effort switch the server-visible model back to `default/default` using the existing model switch packet.

## Consequences

This keeps `NetworkHandler.VERSION`, packet ids, handshake, and model sync formats unchanged.

Other players only see the server-visible model, normally `default/default`. If `CanSwitchModel=false` or another server-side rule rejects the fallback switch, a pure client cannot force the server-visible model to change; it can still keep the local model private and avoid leaking local ids or local state.
