# OpenYSM Domain Context

## Terms

- Server model: a YSM model known to the connected server. The server validates its model id and texture id, syncs the model payload to clients, and broadcasts a player's selected server model to other players.
- Local model: a YSM model loaded only by one client from `config/yes_steve_model/custom`. Local model ids are prefixed with `local/` and are never sent to the server.
- Server-visible model: the model state other players can observe for a player. When a local model is active, OpenYSM tries to keep the server-visible model at `default/default`.

## Local Model Overlay

Local models are a client-side overlay on top of the server model table. They are added to the client model registry under a synthetic `local/` root pack shown as "Local Models" / "本地模型".

Selecting a local model changes only the local player's `PlayerCapability`. The client saves that choice per server address and player UUID in `config/yes_steve_model/local_selection.json`, then asks the server to switch the server-visible model back to `default/default`.

The network protocol is unchanged. Local model ids must not appear in model switching, texture switching, animation, MoLang, or feedback packets. If a server refuses the fallback default switch, a pure client cannot force other players to see default; it can only prevent the local model id and state from leaking.
