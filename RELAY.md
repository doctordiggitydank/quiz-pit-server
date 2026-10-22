# The Quiz Pit — camera relay

This is the one piece of The Quiz Pit that runs **outside** the Elements
app: a small [MediaMTX](https://mediamtx.org) container that receives each
cohost's webcam (over WHIP/WebRTC) and hands it back out as HLS for
Unreal Engine to display on the trolls.

```
cohost's browser  --WHIP-->  MediaMTX  --HLS-->  show PC (Unreal, slots B/C)
     (camera page)          (this box)          reads https://<domain>/relay/hls/...
```

Why not inside Elements: Elements serves HTTP apps; a live camera needs a
real UDP media path. MediaMTX is the smallest thing that does exactly this
job — a single stateless process, no database, no transcoding (the H.264
720p60 feed passes straight through).

## What's needed (the whole ask)

1. **Run the container** — `relay/mediamtx.yml` + `relay/docker-compose.yml`
   in this directory. One `docker compose up -d`, nothing else to install.
2. **UDP 8189 reachable from the internet** — this is the only genuinely
   new network exposure. It's the WebRTC media port; without it no camera
   can connect. (TCP 8889 and 8888 stay private — only the reverse proxy
   below talks to them.)
3. **Two reverse-proxy routes** on the existing HTTPS domain, in front of
   Elements (so they win over the app's own `/app/...` routes):

   | Public URL | Forwards to | Purpose |
   |---|---|---|
   | `POST/DELETE/PATCH /relay/whip/*` | `127.0.0.1:8889/` | WHIP handshake (also answers OPTIONS) |
   | `GET /relay/hls/*` | `127.0.0.1:8888/` | HLS playlists + segments |

nginx:

```nginx
location /relay/whip/ {
    proxy_pass http://127.0.0.1:8889/;
    proxy_redirect / /relay/whip/;
}
location /relay/hls/ {
    proxy_pass http://127.0.0.1:8888/;
    proxy_redirect / /relay/hls/;
    add_header Cache-Control "no-cache" always;
}
```

Caddy:

```
handle_path /relay/whip/* {
    reverse_proxy {
        to 127.0.0.1:8889
        header_down Location ^/ /relay/whip/
    }
}
handle_path /relay/hls/* {
    reverse_proxy {
        to 127.0.0.1:8888
        header_down Location ^/ /relay/hls/
    }
}
```

(The prefix strip + `Location` rewrite is MediaMTX's documented
[subfolder setup](https://mediamtx.org/docs/features/expose-the-server-in-a-subfolder) —
it makes WHIP session URLs resolve back through the proxy.)

That's it. No new VMs, no paid services, nothing to babysit.

## It does not have to run all the time

MediaMTX is stateless: idle it uses ~100 MB of RAM, ~0% CPU, and **zero
bandwidth** (with no camera publishing there is literally no stream —
`source: publisher` means the stream exists only while someone is live).
Two supported modes:

- **Leave it running** (`restart: unless-stopped` as shipped): survives
  reboots, nothing to remember on show day. Recommended — the idle cost is
  a rounding error and it removes a failure point.
- **On demand**: before each show `docker start mediamtx` (or
  `docker compose up -d`); after the show `docker stop mediamtx`. This is
  step 1 of the show-day checklist. One caveat: the cohost's browser
  cannot wake it for you — a stopped relay just refuses the connection —
  so "on demand" means a human runs that one command a few minutes before
  the show. It's in the checklist so it can't be forgotten.

On Kubernetes the same shape works: a Deployment (config in a ConfigMap)
scaled `0 → 1` before the show; with `hostNetwork: true` the ICE
behaviour matches the compose file.

## Verify it's working

```bash
# WHIP endpoint alive — should return ICE server links:
curl -s -D- -X OPTIONS https://<domain>/relay/whip/camb/whip -o /dev/null | grep -i '^link:'

# HLS before any camera is live — 404 is NORMAL here (stream doesn't
# exist until a cohost presses Go Live):
curl -s -o /dev/null -w '%{http_code}\n' https://<domain>/relay/hls/camb/index.m3u8

# End-to-end: cohost opens
#   https://<domain>/app/static/quizpit/player/index.html?slot=B
# presses Go Live — then the HLS URL above turns 200 and the page shows
# a live fps/Mbps readout.
```

## Capacity

- CPU/RAM: passthrough only — roughly 0.5 CPU + 512 MB for two 720p60
  streams at peak; idle ~100 MB.
- Bandwidth: 2 cameras × 2 Mbps up ≈ **29 GB/month** at the show cadence
  (4 shows × 4 h). At 6 Mbps it would be ~86 GB — the camera page caps
  each feed at 2 Mbps precisely to keep this small. On a residential or
  on-prem box this is free; on cloud it would be ~$2–4.
- Disk: nothing recorded, nothing stored.

## Optional hardening (not needed day one)

The two stream paths are unauthenticated but restricted to `camb`/`camc`
only (the config's scoped `authInternalUsers`). If the domain is public,
anyone who finds those URLs could watch the cohost cameras or push a joke
feed. To lock it down:

1. In `mediamtx.yml`, change the `publish` permissions to a named user
   with a password (see MediaMTX auth docs), keeping `read` open — Unreal
   cannot send credentials, so HLS reads must stay open.
2. Share the camera-page link with the password as query params
   (`...?slot=B&user=show&pass=<password>`); the page sends them as HTTP
   Basic auth on the WHIP request.

For a once-a-week private show this is optional; treat it as a follow-up
if the stream ever matters.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Page says "relay unreachable" | Container not running (`docker start mediamtx`), or the `/relay/whip/` proxy route missing. |
| OPTIONS returns no `Link:` header / cameras connect but video never flows | ICE can't traverse: check UDP 8189 is actually published and reachable (`nc -vu` / cloud firewall rules allow UDP 8189). Docker Desktop (bridge mode) also needs `webrtcIPsFromInterfaces: false` + `webrtcAdditionalHosts: ["<public IP>"]` in the config. |
| WHIP 409 | That slot is already live — the previous browser session didn't release it; wait a few seconds (the config lets a new publisher take the path) or press Stop in the old tab. |
| WHIP 404 | Config not loaded / wrong paths — confirm `docker logs mediamtx` shows `camb` and `camc` paths. |
| HLS 404 in Unreal but page is LIVE | Unusual — check `docker logs mediamtx`; the proxy `/relay/hls/` route may be missing. |

Logs: `docker logs -f mediamtx`.
