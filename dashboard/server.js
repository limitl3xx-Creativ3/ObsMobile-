#!/data/data/com.termux/files/usr/bin/node
/**
 * OBS Mobile — Live Build Dashboard
 * Serves the web UI and proxies the GitHub API so the browser can poll
 * build status and APK availability without CORS/token issues.
 *
 *   node dashboard/server.js            → http://localhost:8080
 *   REPO=owner/name node dashboard/server.js
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 8080;
const REPO_FILE = path.join(__dirname, "config.json");

function getRepo() {
  if (process.env.REPO) return process.env.REPO;
  try { return JSON.parse(fs.readFileSync(REPO_FILE, "utf8")).repo; } catch {}
  return "";
}
function setRepo(repo) {
  fs.writeFileSync(REPO_FILE, JSON.stringify({ repo }, null, 2));
}

function fetchJSON(url) {
  return fetch(url, {
    headers: { "User-Agent": "ObsMobile-Dashboard", Accept: "application/vnd.github+json" },
  }).then(r => (r.ok ? r.json() : null));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (url.pathname === "/api/repo" && req.method === "POST") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", () => {
      try {
        const repo = JSON.parse(body).repo.trim();
        if (/^[\w.-]+\/[\w.-]+$/.test(repo)) setRepo(repo);
      } catch {}
      res.writeHead(200); res.end("ok");
    });
    return;
  }

  if (url.pathname === "/api/status") {
    const repo = getRepo();
    if (!repo) { res.writeHead(200, { "Content-Type": "application/json" }); res.end(JSON.stringify({ repo: null })); return; }

    const [runs, release] = await Promise.all([
      fetchJSON(`https://api.github.com/repos/${repo}/actions/workflows/build.yml/runs?per_page=5`),
      fetchJSON(`https://api.github.com/repos/${repo}/releases/tags/latest`).catch(() => null),
    ]);

    const latest = runs?.workflow_runs?.[0];
    const apk = release?.assets?.find(a => a.name.endsWith(".apk"));

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      repo,
      lastPush: latest?.head_commit?.timestamp || null,
      commitMsg: latest?.head_commit?.commit?.message?.split("\n")[0] || null,
      status: latest?.status || null,          // queued | in_progress | completed
      conclusion: latest?.conclusion || null,  // success | failure | null
      updatedAt: latest?.updated_at || null,
      apk: apk ? { name: apk.name, size: apk.size, url: apk.browser_download_url, updated: apk.updated_at } : null,
      runs: (runs?.workflow_runs || []).map(r => ({
        status: r.status, conclusion: r.conclusion, updatedAt: r.updated_at, url: r.html_url,
      })),
    }));
    return;
  }

  if (url.pathname === "/") {
    res.writeHead(200, { "Content-Type": "text/html" });
    res.end(fs.readFileSync(path.join(__dirname, "index.html")));
    return;
  }

  res.writeHead(404); res.end("not found");
});

server.listen(PORT, () => {
  console.log(`OBS Mobile dashboard → http://localhost:${PORT}`);
});
