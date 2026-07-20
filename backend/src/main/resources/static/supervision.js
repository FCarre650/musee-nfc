(() => {
  const STORAGE_KEY = "musee-nfc-supervision-session";

  const loginScreen = document.getElementById("login-screen");
  const dashboardScreen = document.getElementById("dashboard-screen");
  const loginForm = document.getElementById("login-form");
  const loginError = document.getElementById("login-error");
  const roomsGrid = document.getElementById("rooms-grid");
  const roomTemplate = document.getElementById("room-card-template");
  const connDot = document.getElementById("conn-dot");
  const connLabel = document.getElementById("conn-label");
  const clockEl = document.getElementById("clock");
  const whoamiEl = document.getElementById("whoami");
  const logoutBtn = document.getElementById("logout-btn");

  /** @type {{token: string, fullName: string, role: string} | null} */
  let session = null;
  /** rooms indexed by checkpointId, kept up to date between server pushes for the live countdown */
  let rooms = new Map();
  let socket = null;
  let pollTimer = null;
  let reconnectTimer = null;

  function loadSession() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  function saveSession(s) {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  }

  function clearSession() {
    sessionStorage.removeItem(STORAGE_KEY);
  }

  function showLogin(message) {
    dashboardScreen.classList.add("hidden");
    loginScreen.classList.remove("hidden");
    if (message) {
      loginError.textContent = message;
      loginError.classList.remove("hidden");
    } else {
      loginError.classList.add("hidden");
    }
  }

  function showDashboard() {
    loginScreen.classList.add("hidden");
    dashboardScreen.classList.remove("hidden");
    whoamiEl.textContent = `${session.fullName} (${session.role})`;
  }

  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const login = document.getElementById("login-input").value.trim();
    const password = document.getElementById("password-input").value;

    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ login, password }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ error: "Échec de connexion" }));
        showLogin(body.error || "Échec de connexion");
        return;
      }
      const data = await res.json();
      if (data.guard.role !== "SUPERVISOR" && data.guard.role !== "ADMIN") {
        showLogin("Ce compte gardien n'a pas accès à la supervision (chef de poste ou direction requis).");
        return;
      }
      session = { token: data.token, fullName: data.guard.fullName, role: data.guard.role };
      saveSession(session);
      start();
    } catch (err) {
      showLogin("Serveur injoignable. Vérifiez qu'il tourne bien sur ce port.");
    }
  });

  logoutBtn.addEventListener("click", () => {
    stop();
    clearSession();
    session = null;
    showLogin();
  });

  function start() {
    showDashboard();
    refreshSnapshot();
    connectWebSocket();
  }

  function stop() {
    if (socket) { socket.onclose = null; socket.close(); socket = null; }
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  }

  async function refreshSnapshot() {
    try {
      const res = await fetch("/api/supervision/snapshot", {
        headers: { Authorization: `Bearer ${session.token}` },
      });
      if (res.status === 401 || res.status === 403) {
        clearSession();
        showLogin("Session expirée, reconnectez-vous.");
        stop();
        return;
      }
      const data = await res.json();
      applySnapshot(data);
    } catch {
      // silencieux : la connexion WS/polling suivante réessaiera
    }
  }

  function connectWebSocket() {
    const protocol = location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${protocol}://${location.host}/ws/supervision?token=${encodeURIComponent(session.token)}`);

    socket.onopen = () => {
      setConnState("live");
      if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    };

    socket.onmessage = (event) => {
      applySnapshot(JSON.parse(event.data));
    };

    socket.onclose = () => {
      setConnState("polling");
      startPolling();
      reconnectTimer = setTimeout(connectWebSocket, 8000);
    };

    socket.onerror = () => socket.close();
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(refreshSnapshot, 5000);
  }

  function setConnState(state) {
    connDot.className = "dot " + (state === "live" ? "live" : "polling");
    connLabel.textContent = state === "live" ? "temps réel (WebSocket)" : "reconnexion… (secours par rafraîchissement)";
  }

  function applySnapshot(snapshot) {
    for (const room of snapshot.rooms) {
      rooms.set(room.checkpointId, {
        ...room,
        lastScanAtMs: room.lastScanAt ? Date.parse(room.lastScanAt) : null,
      });
    }
    renderAll();
  }

  function renderAll() {
    const sorted = [...rooms.values()].sort((a, b) => a.roomName.localeCompare(b.roomName, "fr"));
    roomsGrid.innerHTML = "";
    for (const room of sorted) {
      roomsGrid.appendChild(renderRoomCard(room));
    }
  }

  function renderRoomCard(room) {
    const node = roomTemplate.content.cloneNode(true);
    const article = node.querySelector(".room-card");
    article.dataset.checkpointId = room.checkpointId;
    node.querySelector(".room-name").textContent = room.roomName;
    node.querySelector(".room-zone").textContent = room.zone || "";
    node.querySelector(".threshold").textContent = `seuil ${room.alertThresholdMin} min`;
    updateRoomLiveFields(article, room);
    return node;
  }

  /** Calcule l'état vert/orange/rouge côté client, en miroir de SupervisionService.kt côté serveur,
   *  pour que le chrono avance et change de couleur seconde par seconde sans dépendre d'un nouveau push. */
  function computeState(elapsedSeconds, thresholdMin) {
    if (elapsedSeconds == null) return "RED";
    const thresholdSeconds = thresholdMin * 60;
    if (elapsedSeconds < (thresholdSeconds * 2) / 3) return "GREEN";
    if (elapsedSeconds < thresholdSeconds) return "ORANGE";
    return "RED";
  }

  function formatElapsed(elapsedSeconds) {
    if (elapsedSeconds == null) return "Jamais contrôlée";
    const s = Math.max(0, Math.floor(elapsedSeconds));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) return `il y a ${h} h ${String(m).padStart(2, "0")} min`;
    if (m > 0) return `il y a ${m} min ${String(sec).padStart(2, "0")} s`;
    return `il y a ${sec} s`;
  }

  function updateRoomLiveFields(article, room) {
    const elapsedSeconds = room.lastScanAtMs != null ? (Date.now() - room.lastScanAtMs) / 1000 : null;
    const state = computeState(elapsedSeconds, room.alertThresholdMin);
    article.className = `room-card state-${state}`;
    article.querySelector(".elapsed").textContent = formatElapsed(elapsedSeconds);
    article.querySelector(".last-guard").textContent = room.lastGuardName
      ? `dernier contrôle : ${room.lastGuardName}`
      : "aucun contrôle enregistré";
  }

  // Boucle de 1s : fait vivre les compteurs et bascule les couleurs sans attendre le serveur.
  setInterval(() => {
    clockEl.textContent = new Date().toLocaleTimeString("fr-FR");
    if (dashboardScreen.classList.contains("hidden")) return;
    for (const article of roomsGrid.querySelectorAll(".room-card")) {
      const room = rooms.get(Number(article.dataset.checkpointId));
      if (room) updateRoomLiveFields(article, room);
    }
  }, 1000);

  // Bootstrap
  session = loadSession();
  if (session) {
    start();
  } else {
    showLogin();
  }
})();
