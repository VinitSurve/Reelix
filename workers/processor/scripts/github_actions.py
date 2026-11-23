#!/usr/bin/env python3
"""Helper script to interact with GitHub Actions workflows for Reelix."""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request


def get_github_token() -> str:
    out = subprocess.check_output(
        "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill | grep password= | cut -d= -f2",
        shell=True,
    )
    return out.decode().strip()


def github_api(endpoint: str, method: str = "GET", data: dict = None) -> dict:
    token = get_github_token()
    url = f"https://api.github.com/repos/VinitSurve/Reelix/{endpoint.lstrip('/')}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "Reelix-Agent",
    }
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read()
            if content:
                return json.loads(content.decode())
            return {}
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode()
        print(f"HTTPError {e.code} on {method} {url}: {err_msg}", file=sys.stderr)
        raise


def dispatch(workflow_name: str, inputs: dict = None, ref: str = "main"):
    payload = {"ref": ref}
    if inputs:
        payload["inputs"] = inputs
    github_api(f"actions/workflows/{workflow_name}/dispatches", method="POST", data=payload)
    print(f"Dispatched workflow: {workflow_name} with ref={ref}, inputs={inputs}")


def get_latest_run(workflow_name: str) -> dict:
    res = github_api(f"actions/workflows/{workflow_name}/runs?per_page=5")
    runs = res.get("workflow_runs", [])
    if not runs:
        return {}
    return runs[0]


def poll_run(run_id: int, timeout_sec: int = 900, interval_sec: int = 10) -> dict:
    start_time = time.time()
    last_status = None
    while time.time() - start_time < timeout_sec:
        run = github_api(f"actions/runs/{run_id}")
        status = run.get("status")
        conclusion = run.get("conclusion")
        if status != last_status:
            print(f"Run {run_id} status: {status} (conclusion: {conclusion})", flush=True)
            last_status = status
        if status == "completed":
            return run
        time.sleep(interval_sec)
    raise TimeoutError(f"Workflow run {run_id} did not complete within {timeout_sec}s")


def print_run_logs(run_id: int):
    res = github_api(f"actions/runs/{run_id}/jobs")
    token = get_github_token()
    for job in res.get("jobs", []):
        job_id = job.get("id")
        print(f"\n--- Job: {job.get('name')} (Status: {job.get('status')}, Conclusion: {job.get('conclusion')}, ID: {job_id}) ---")
        for step in job.get("steps", []):
            print(f"  Step: {step.get('name')} -> {step.get('conclusion')}")
        
        # Download job raw log
        log_url = f"https://api.github.com/repos/VinitSurve/Reelix/actions/jobs/{job_id}/logs"
        class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
                if new_req:
                    for k in list(new_req.headers.keys()):
                        if k.lower() == "authorization":
                            del new_req.headers[k]
                return new_req

        opener = urllib.request.build_opener(NoAuthRedirect)
        req = urllib.request.Request(log_url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
        try:
            with opener.open(req) as resp:
                log_data = resp.read().decode(errors="replace")
                print(f"\n--- Detailed Logs for Job {job_id} (Last 80 lines) ---")
                lines = log_data.splitlines()
                print("\n".join(lines[-80:]))
        except Exception as e:
            print(f"Could not retrieve logs for job {job_id}: {e}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: github_actions.py <dispatch|runs|poll|logs> [args...]")
        sys.exit(1)

    cmd = sys.argv[1]
    if cmd == "dispatch":
        wf = sys.argv[2]
        inputs = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None
        dispatch(wf, inputs)
    elif cmd == "runs":
        wf = sys.argv[2]
        runs = github_api(f"actions/workflows/{wf}/runs?per_page=5").get("workflow_runs", [])
        for r in runs:
            print(f"ID: {r.get('id')} | Status: {r.get('status')} | Conclusion: {r.get('conclusion')} | Created: {r.get('created_at')} | URL: {r.get('html_url')}")
    elif cmd == "poll":
        rid = int(sys.argv[2])
        r = poll_run(rid)
        print(f"Final conclusion: {r.get('conclusion')}")
        print_run_logs(rid)
    elif cmd == "logs":
        rid = int(sys.argv[2])
        print_run_logs(rid)
