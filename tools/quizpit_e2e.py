#!/usr/bin/env python3
"""Quiz Pit stage-socket E2E against the temp instance (port-forwarded)."""
import json
import subprocess
import sys
import time

import websocket

POD = "namazu-instance-namazu-agent-opencode-ef436bfe-ljwld"
URL = "ws://localhost:18080/element/quizpit/ws/stage"
failures = []


def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" - {detail}" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def drain(ws, want_types, timeout=12):
    """Collect messages until all want_types seen (or timeout)."""
    seen = {}
    ws.settimeout(2)
    deadline = time.time() + timeout
    while time.time() < deadline and not all(t in seen for t in want_types):
        try:
            msg = json.loads(ws.recv())
        except websocket.WebSocketTimeoutException:
            continue
        seen.setdefault(msg.get("type"), msg)
    return seen


def main():
    fwd = subprocess.Popen(
        ["kubectl", "port-forward", f"pod/{POD}", "18080:8080"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(3)
    try:
        ws = websocket.create_connection(URL, timeout=12)
        hello_state = drain(ws, ["hello", "state"], timeout=15)
        check("hello received", "hello" in hello_state)
        check("state received", "state" in hello_state)
        hello = hello_state.get("hello", {})
        check("twitch unlinked in hello",
              hello.get("twitch", {}).get("linked") is False, json.dumps(hello)[:120])
        check("guests array in hello", isinstance(hello.get("guests"), list))

        # in-memory game state survives reconnects (by design) — reset to LOBBY
        if hello_state.get("state", {}).get("phase") != "LOBBY":
            ws.send(json.dumps({"type": "host_reset"}))
            hello_state = drain(ws, ["state"], timeout=12)
        check("lobby phase", hello_state.get("state", {}).get("phase") == "LOBBY",
              json.dumps(hello_state.get("state", {}))[:160])

        # start the show
        ws.send(json.dumps({"type": "host_start"}))
        started = drain(ws, ["show_start", "state"], timeout=15)
        check("show_start", "show_start" in started)
        st = started.get("state", {})
        check("phase QUESTION", st.get("phase") == "QUESTION", json.dumps(st)[:200])
        check("question has 4 choices",
              isinstance(st.get("question", {}).get("choices"), list)
              and len(st["question"]["choices"]) == 4)
        check("no correctIndex leak while open",
              "correctIndex" not in (st.get("question") or {}))

        # ticks should arrive every second
        ticks = drain(ws, ["tick"], timeout=6)
        check("tick arrives", "tick" in ticks, json.dumps(ticks)[:100])

        # reveal
        ws.send(json.dumps({"type": "host_reveal"}))
        revealed = drain(ws, ["state"], timeout=12)
        st = revealed.get("state", {})
        check("phase REVEAL", st.get("phase") == "REVEAL", json.dumps(st)[:200])
        q = st.get("question") or {}
        check("correctIndex present on reveal", "correctIndex" in q)
        check("reveal block present",
              isinstance(st.get("reveal"), dict) and "answers" in st.get("reveal", {}))

        # next question
        ws.send(json.dumps({"type": "host_next"}))
        nxt = drain(ws, ["state"], timeout=12)
        st = nxt.get("state", {})
        check("phase QUESTION again", st.get("phase") == "QUESTION")
        check("question index advanced", st.get("questionIndex") == 1,
              f"index={st.get('questionIndex')}")

        # host panel socket joins mid-show and sees current state
        host = websocket.create_connection(
            "ws://localhost:18080/element/quizpit/ws/host", timeout=12)
        host_hello = drain(host, ["hello", "state"], timeout=15)
        check("host panel hello", "hello" in host_hello)
        check("host panel sees QUESTION state",
              host_hello.get("state", {}).get("phase") == "QUESTION")
        host.close()

        # end the show (host panel's connect re-broadcasts QUESTION state to
        # the stage socket too — drain until we actually see GAME_OVER)
        ws.send(json.dumps({"type": "host_end"}))
        seen_phase = None
        ws.settimeout(2)
        deadline = time.time() + 12
        while time.time() < deadline:
            try:
                msg = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            if msg.get("type") == "state":
                seen_phase = msg.get("phase")
                if seen_phase == "GAME_OVER":
                    break
        check("phase GAME_OVER", seen_phase == "GAME_OVER", f"last phase={seen_phase}")

        ws.close()
    finally:
        fwd.terminate()

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
