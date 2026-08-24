(() => {
  "use strict";

  // Same Vercel cloud-backup proxy the Android app talks to (app/build.gradle.kts).
  const BASE_URL = "https://fitbuddy-cloud-backup.vercel.app";
  const API_KEY_BLOB = "C1MPVgBWDwBLD1dWU0xWUwdSTFwPA1NJAwFXAQJYUVBdVAAFDgcPBwIAW10dCV8HAk4DAQFTTA0EDgFLUlVTDw==";
  const DB_NAME = "fitbuddy";
  const COLLECTION = "split_bill";
  const MAX_SCHEMA_VERSION = 2;
  const MAX_AUDIT_EVENTS = 100;
  // Same DSN the Android app uses (app/build.gradle.kts SENTRY_DSN_BLOB), decoded once at build time.
  const SENTRY_DSN = "https://bff111d230b93ed7738c5bcb278dab37@o4511756435718144.ingest.de.sentry.io/4511961602195536";

  // The API key is XOR-masked against the room id itself, so it's only ever
  // reconstructed once someone actually types the room id in — not sitting
  // decodable in the file on its own.
  function unmaskApiKey(blob, roomId) {
    const masked = Uint8Array.from(atob(blob), (c) => c.charCodeAt(0));
    const maskBytes = new TextEncoder().encode(roomId);
    const plain = masked.map((b, i) => b ^ maskBytes[i % maskBytes.length]);
    return new TextDecoder().decode(plain);
  }

  const form = document.getElementById("room-form");
  const input = document.getElementById("room-id-input");
  const submitBtn = document.getElementById("room-submit");
  const statusEl = document.getElementById("room-status");
  const resultsEl = document.getElementById("room-results");
  const summaryEl = document.getElementById("room-summary");
  const tbody = document.getElementById("recharge-tbody");
  const table = document.getElementById("recharge-table");
  const emptyEl = document.getElementById("recharge-empty");

  const rechargeForm = document.getElementById("recharge-form");
  const rechargeRoomSelectWrap = document.getElementById("recharge-room-select-wrap");
  const rechargeRoomSelect = document.getElementById("recharge-room-select");
  const rechargeReadingsEl = document.getElementById("recharge-readings");
  const rechargePayerSelect = document.getElementById("recharge-payer");
  const rechargeAmountInput = document.getElementById("recharge-amount");
  const rechargeNoteInput = document.getElementById("recharge-note");
  const rechargeSubmitBtn = document.getElementById("recharge-submit");
  const rechargeStatusEl = document.getElementById("recharge-status");

  if (!form) return;

  // Set once a room loads successfully; used both to render the recharge form
  // and as the "have things changed since I looked at this?" baseline.
  let currentRoomId = null;
  let currentBackupData = null;
  let currentSelectedRoomLocalId = null;

  const LAST_ROOM_ID_KEY = "splitbill_last_room_id";

  function getSavedRoomId() {
    try {
      return localStorage.getItem(LAST_ROOM_ID_KEY);
    } catch {
      return null;
    }
  }

  function saveRoomId(roomId) {
    try {
      localStorage.setItem(LAST_ROOM_ID_KEY, roomId);
    } catch {
      // Storage unavailable (private mode etc.) — just skip persisting.
    }
  }

  /** Remembers which member this browser last logged a recharge as, per room. */
  function getSavedMemberId(roomLocalId) {
    try {
      return localStorage.getItem(`splitbill_web_member_${roomLocalId}`);
    } catch {
      return null;
    }
  }

  function saveMemberId(roomLocalId, memberId) {
    try {
      localStorage.setItem(`splitbill_web_member_${roomLocalId}`, memberId);
    } catch {
      // Storage unavailable — the dropdown just won't default to this next time.
    }
  }

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const roomId = input.value.trim();
    if (!roomId) return;
    await loadRoom(roomId);
  });

  rechargeRoomSelect?.addEventListener("change", () => {
    currentSelectedRoomLocalId = rechargeRoomSelect.value;
    renderRechargeForm(currentBackupData);
  });

  rechargePayerSelect?.addEventListener("change", () => {
    if (currentSelectedRoomLocalId && rechargePayerSelect.value) {
      saveMemberId(currentSelectedRoomLocalId, rechargePayerSelect.value);
    }
  });

  rechargeForm?.addEventListener("submit", (e) => {
    e.preventDefault();
    submitRecharge();
  });

  // Restore the last-used room ID so returning visitors don't retype it.
  const savedRoomId = getSavedRoomId();
  if (savedRoomId) {
    input.value = savedRoomId;
    loadRoom(savedRoomId);
  }

  async function loadRoom(roomId) {
    setBusy(true);
    setStatus("Fetching room…");
    resultsEl.hidden = true;

    try {
      const doc = await fetchBackupDoc(roomId);
      const backupData = await decodeBackupDoc(doc);
      currentRoomId = roomId;
      currentBackupData = backupData;
      currentSelectedRoomLocalId = pickDefaultRoomLocalId(backupData);
      saveRoomId(roomId);
      renderRoom(backupData);
      renderRechargeForm(backupData);
      setStatus("");
      resultsEl.hidden = false;
    } catch (err) {
      setStatus(err.message || "Something went wrong.", true);
    } finally {
      setBusy(false);
    }
  }

  function pickDefaultRoomLocalId(backupData) {
    const rooms = Array.isArray(backupData.rooms) ? backupData.rooms : [];
    return rooms.length ? rooms[0].id : null;
  }

  function setBusy(busy) {
    submitBtn.disabled = busy;
    submitBtn.textContent = busy ? "Loading…" : "Load room";
  }

  function setStatus(message, isError = false) {
    statusEl.textContent = message;
    statusEl.classList.toggle("is-error", Boolean(isError));
  }

  function setRechargeStatus(message, isError = false) {
    rechargeStatusEl.textContent = message;
    rechargeStatusEl.classList.toggle("is-error", Boolean(isError));
  }

  function setRechargeBusy(busy) {
    rechargeSubmitBtn.disabled = busy;
    rechargeSubmitBtn.textContent = busy ? "Logging…" : "Log recharge";
  }

  async function fetchBackupDoc(roomId) {
    const url = new URL(`${BASE_URL}/api/backup/${encodeURIComponent(roomId)}`);
    url.searchParams.set("db", DB_NAME);
    url.searchParams.set("collection", COLLECTION);
    url.searchParams.set("chainSupport", "1");
    url.searchParams.set("chunkId", roomId);
    url.searchParams.set("maxSchemaVersion", String(MAX_SCHEMA_VERSION));

    const apiKey = unmaskApiKey(API_KEY_BLOB, roomId);
    const res = await fetch(url, {
      method: "GET",
      headers: { Authorization: `Bearer ${apiKey}` },
    });

    if (res.status === 401 || res.status === 403) {
      throw new Error("That Room ID isn't recognized.");
    }
    if (res.status === 404) {
      throw new Error("No cloud backup found for that Room ID. Check it's synced from the app at least once.");
    }
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      const parsedMsg = safeJsonErrorMessage(body);
      throw new Error(parsedMsg || `Cloud request failed (HTTP ${res.status}).`);
    }

    const json = await res.json();
    if (!json.payloadJson) throw new Error("Cloud backup response was missing its payload.");
    return json;
  }

  async function pushBackupDoc(roomId, backupData, { deviceName, deviceId, lastAction, lastActionByMember }) {
    const apiKey = unmaskApiKey(API_KEY_BLOB, roomId);
    const payloadJson = await sealCompressed(JSON.stringify(backupData));
    const devicesJson = JSON.stringify(backupData.devices || []);
    const auditLogJson = JSON.stringify(backupData.auditLog || []);

    const body = {
      payloadJson,
      schemaVersion: backupData.version,
      exportedAt: backupData.exportedAt,
      appPackage: "com.anant.splitbill",
      deviceName: deviceName.slice(0, 128),
      macId: deviceId.slice(0, 64),
      chunkId: roomId,
      chunkIndex: 0,
      storageVersion: 2,
      nextChunkId: null,
      tipChunkId: roomId,
      deviceCount: Math.max(0, (backupData.devices || []).length),
      devicesJson: devicesJson.slice(0, 50000),
      auditLogJson: auditLogJson.slice(0, 100000),
      lastAction: lastAction.slice(0, 64),
      lastActionByMember: lastActionByMember.slice(0, 128),
    };

    const url = new URL(`${BASE_URL}/api/backup/${encodeURIComponent(roomId)}`);
    url.searchParams.set("db", DB_NAME);
    url.searchParams.set("collection", COLLECTION);
    url.searchParams.set("chainSupport", "1");
    url.searchParams.set("chunkId", roomId);

    const res = await fetch(url, {
      method: "PUT",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const bodyText = await res.text().catch(() => "");
      const parsedMsg = safeJsonErrorMessage(bodyText);
      throw new Error(parsedMsg || `Cloud push failed (HTTP ${res.status}).`);
    }
  }

  function safeJsonErrorMessage(body) {
    if (!body) return null;
    try {
      const obj = JSON.parse(body);
      return typeof obj.error === "string" ? obj.error : null;
    } catch {
      return null;
    }
  }

  async function decodeBackupDoc(doc) {
    const raw = doc.payloadJson;
    const envelope = safeJsonParse(raw);

    // Legacy pre-envelope backups are the plain BackupData JSON itself.
    if (!envelope || !("splitbillBackup" in envelope)) {
      if (envelope && "version" in envelope) return envelope;
      throw new Error("Couldn't recognize this room's backup format.");
    }

    if (envelope.enc === "AES-GCM") {
      throw new Error("This room's backup is password-protected and can't be previewed in the browser. Open it in the app instead.");
    }
    if (envelope.enc !== "none") {
      throw new Error("Unrecognized backup encoding.");
    }

    const bytes = base64ToBytes(envelope.ciphertext);
    const plainBytes = envelope.compression === "gzip" ? await gunzip(bytes) : bytes;
    const json = new TextDecoder().decode(plainBytes);
    const backupData = safeJsonParse(json);
    if (!backupData) throw new Error("Room data was corrupt or unreadable.");
    return backupData;
  }

  async function sealCompressed(payloadJson) {
    const plainBytes = new TextEncoder().encode(payloadJson);
    const compressed = await gzip(plainBytes);
    const envelope = {
      splitbillBackup: 1,
      enc: "none",
      compression: "gzip",
      ciphertext: bytesToBase64(compressed),
    };
    return JSON.stringify(envelope);
  }

  function safeJsonParse(text) {
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  function base64ToBytes(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 8192;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
    }
    return btoa(binary);
  }

  async function gunzip(bytes) {
    if (typeof DecompressionStream === "undefined") {
      throw new Error("Your browser doesn't support decompressing this backup. Try a recent Chrome, Edge, Firefox, or Safari.");
    }
    const ds = new DecompressionStream("gzip");
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    const buf = await new Response(stream).arrayBuffer();
    return new Uint8Array(buf);
  }

  async function gzip(bytes) {
    if (typeof CompressionStream === "undefined") {
      throw new Error("Your browser doesn't support compressing data. Try a recent Chrome, Edge, Firefox, or Safari.");
    }
    const cs = new CompressionStream("gzip");
    const stream = new Blob([bytes]).stream().pipeThrough(cs);
    const buf = await new Response(stream).arrayBuffer();
    return new Uint8Array(buf);
  }

  function renderRoom(backupData) {
    const rooms = Array.isArray(backupData.rooms) ? backupData.rooms : [];
    const entries = Array.isArray(backupData.entries) ? backupData.entries : [];
    const roomsById = new Map(rooms.map((r) => [r.id, r]));
    const multiRoom = rooms.length > 1;

    summaryEl.innerHTML = "";
    const names = rooms.length ? rooms.map((r) => r.name).join(", ") : "Unnamed room";
    const exportedAt = backupData.exportedAt ? new Date(backupData.exportedAt).toLocaleString() : "unknown";
    const summaryP = document.createElement("p");
    summaryP.className = "fine";
    summaryP.textContent = `${names} · last synced ${exportedAt}`;
    summaryEl.appendChild(summaryP);

    const headRow = table.querySelector("thead tr");
    let roomTh = headRow.querySelector(".room-col-room");
    if (multiRoom && !roomTh) {
      roomTh = document.createElement("th");
      roomTh.className = "room-col-room";
      roomTh.textContent = "Room";
      headRow.insertBefore(roomTh, headRow.firstChild);
    } else if (!multiRoom && roomTh) {
      roomTh.remove();
    }

    const recharges = entries
      .filter((e) => e.type === "RECHARGE" && !e.deleted)
      .sort((a, b) => b.timestampEpochMs - a.timestampEpochMs);

    tbody.innerHTML = "";
    emptyEl.hidden = recharges.length > 0;
    table.hidden = recharges.length === 0;

    for (const entry of recharges) {
      const room = roomsById.get(entry.roomId);
      const currency = room?.currencySymbol || "Rs.";
      const tr = document.createElement("tr");

      if (multiRoom) {
        const roomTd = document.createElement("td");
        roomTd.textContent = room?.name || entry.roomId;
        tr.appendChild(roomTd);
      }

      const dateTd = document.createElement("td");
      dateTd.textContent = new Date(entry.timestampEpochMs).toLocaleString();
      tr.appendChild(dateTd);

      const memberTd = document.createElement("td");
      memberTd.textContent = entry.memberName || "—";
      tr.appendChild(memberTd);

      const amountTd = document.createElement("td");
      amountTd.textContent = `${currency}${Number(entry.value).toFixed(2)}`;
      tr.appendChild(amountTd);

      const loggedByTd = document.createElement("td");
      loggedByTd.textContent = entry.loggedByMemberName || "—";
      if (entry.loggedByMemberId) {
        loggedByTd.appendChild(document.createTextNode(" "));
        loggedByTd.appendChild(memberIdChip(entry.loggedByMemberId));
      }
      tr.appendChild(loggedByTd);

      const noteTd = document.createElement("td");
      noteTd.textContent = entry.note || "";
      tr.appendChild(noteTd);

      tbody.appendChild(tr);
    }
  }

  /**
   * Truncated, hoverable/clickable member ID chip. Hover shows the full ID via
   * the native title tooltip; click copies it. The member-ID-to-device mapping
   * itself stays in cloud storage only — this chip never exposes device info.
   */
  function memberIdChip(id) {
    const chip = document.createElement("span");
    chip.className = "member-id-chip";
    chip.textContent = id.length > 8 ? `${id.slice(0, 8)}…` : id;
    chip.title = id;
    chip.tabIndex = 0;
    chip.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(id);
        chip.classList.add("member-id-chip--copied");
        setTimeout(() => chip.classList.remove("member-id-chip--copied"), 1200);
      } catch {
        // Clipboard API unavailable (e.g. insecure context) — hover tooltip still shows the full ID.
      }
    });
    return chip;
  }

  // ── Log a recharge ──────────────────────────────────────

  function roomMembers(backupData, roomLocalId) {
    const members = Array.isArray(backupData.members) ? backupData.members : [];
    return members.filter((m) => m.roomId === roomLocalId).sort((a, b) => a.sortOrder - b.sortOrder);
  }

  function renderRechargeForm(backupData) {
    if (!rechargeForm) return;
    const rooms = Array.isArray(backupData.rooms) ? backupData.rooms : [];

    if (rooms.length === 0) {
      rechargeForm.hidden = true;
      return;
    }
    rechargeForm.hidden = false;

    const multiRoom = rooms.length > 1;
    rechargeRoomSelectWrap.hidden = !multiRoom;
    if (multiRoom) {
      rechargeRoomSelect.innerHTML = "";
      for (const r of rooms) {
        const opt = document.createElement("option");
        opt.value = r.id;
        opt.textContent = r.name;
        rechargeRoomSelect.appendChild(opt);
      }
      rechargeRoomSelect.value = currentSelectedRoomLocalId;
    }

    const room = rooms.find((r) => r.id === currentSelectedRoomLocalId) || rooms[0];
    const members = roomMembers(backupData, room.id);
    const entries = (Array.isArray(backupData.entries) ? backupData.entries : []).filter((e) => e.roomId === room.id);
    const state = window.SplitBillEngine.rebuild(members, entries);
    const lastReadingByMember = new Map(state.members.map((m) => [m.memberId, m.lastReading]));

    rechargeReadingsEl.innerHTML = "";
    for (const m of members) {
      const wrap = document.createElement("div");
      wrap.className = "field recharge-reading-field";
      const label = document.createElement("label");
      label.setAttribute("for", `reading-${m.id}`);
      label.textContent = `${m.name}'s reading`;
      const inputEl = document.createElement("input");
      inputEl.type = "number";
      inputEl.step = "0.01";
      inputEl.min = "0";
      inputEl.inputMode = "decimal";
      inputEl.id = `reading-${m.id}`;
      inputEl.dataset.memberId = m.id;
      inputEl.required = true;
      wrap.appendChild(label);
      wrap.appendChild(inputEl);
      const currentP = document.createElement("p");
      currentP.className = "reading-current";
      currentP.textContent = `Last: ${(lastReadingByMember.get(m.id) || 0).toFixed(2)}`;
      wrap.appendChild(currentP);
      rechargeReadingsEl.appendChild(wrap);
    }

    rechargePayerSelect.innerHTML = "";
    for (const m of members) {
      const opt = document.createElement("option");
      opt.value = m.id;
      opt.textContent = m.name;
      rechargePayerSelect.appendChild(opt);
    }
    // Default to whoever this browser logged a recharge as last time, if still a member.
    const savedMemberId = getSavedMemberId(room.id);
    if (savedMemberId && members.some((m) => m.id === savedMemberId)) {
      rechargePayerSelect.value = savedMemberId;
    }
  }

  async function submitRecharge() {
    if (!currentRoomId || !currentBackupData) return;
    const roomLocalId = currentSelectedRoomLocalId;
    const payerId = rechargePayerSelect.value;
    const amount = Number(rechargeAmountInput.value);
    const note = rechargeNoteInput.value.trim();

    if (!payerId) {
      setRechargeStatus("Pick who paid.", true);
      return;
    }
    if (!(amount > 0)) {
      setRechargeStatus("Amount must be greater than zero.", true);
      return;
    }

    const readingInputs = [...rechargeReadingsEl.querySelectorAll("input[data-member-id]")];
    const readings = {};
    for (const inp of readingInputs) {
      const val = Number(inp.value);
      if (inp.value.trim() === "" || Number.isNaN(val)) {
        setRechargeStatus("Enter a meter reading for every member.", true);
        return;
      }
      readings[inp.dataset.memberId] = val;
    }

    setRechargeBusy(true);
    setRechargeStatus("Checking for newer changes…");

    try {
      // Pull the latest doc and compare against what's on screen — if anything
      // relevant changed since this page loaded, refuse to push (would clobber it).
      const freshDoc = await fetchBackupDoc(currentRoomId);
      const freshBackupData = await decodeBackupDoc(freshDoc);

      if (comparableSnapshot(freshBackupData) !== comparableSnapshot(currentBackupData)) {
        currentBackupData = freshBackupData;
        renderRoom(freshBackupData);
        renderRechargeForm(freshBackupData);
        setRechargeStatus(
          "New data has been synced to this room since you loaded it. The page above has been refreshed — please review it and try logging your recharge again.",
          true
        );
        return;
      }

      const room = freshBackupData.rooms.find((r) => r.id === roomLocalId);
      if (!room) throw new Error("That room no longer exists in this backup.");
      const members = roomMembers(freshBackupData, room.id);
      const entries = freshBackupData.entries.filter((e) => e.roomId === room.id);
      const state = window.SplitBillEngine.rebuild(members, entries);
      const byId = new Map(state.members.map((m) => [m.memberId, m]));

      for (const m of members) {
        const newVal = readings[m.id];
        const prev = byId.get(m.id)?.lastReading || 0;
        if (newVal < prev) {
          throw new Error(`${m.name}'s reading (${newVal}) can't be less than the last one (${prev}).`);
        }
      }

      const payer = members.find((m) => m.id === payerId);
      if (!payer) throw new Error("Selected payer is no longer in this room.");

      const groupId = crypto.randomUUID();
      const nowMs = Date.now();
      const { entries: newEntries } = window.SplitBillEngine.recordReadingsAndRecharge({
        roomId: room.id,
        members,
        current: state,
        readings,
        rechargeMemberId: payerId,
        rechargeAmount: amount,
        nowEpochMs: nowMs,
        groupId,
      });
      const rechargeEntry = newEntries.find((e) => e.type === "RECHARGE");
      if (rechargeEntry && note) rechargeEntry.note = note;

      const deviceId = getOrCreateDeviceId();
      const deviceName = describeDevice();
      const ip = await fetchPublicIp();

      const auditDetail = buildAuditDetail({
        payerName: payer.name,
        amount,
        note,
        ip,
        deviceId,
      });

      const updatedDevices = upsertDevice(freshBackupData.devices || [], {
        deviceId,
        deviceName,
        memberId: payer.id,
        memberName: payer.name,
        nowMs,
      });
      const updatedAuditLog = appendAudit(freshBackupData.auditLog || [], {
        action: "record_recharge_web",
        deviceId,
        deviceName,
        memberId: payer.id,
        memberName: payer.name,
        entryId: rechargeEntry?.id || null,
        groupId,
        detail: auditDetail,
        nowMs,
      });

      const updatedBackupData = {
        ...freshBackupData,
        exportedAt: nowMs,
        entries: [...freshBackupData.entries, ...newEntries],
        devices: updatedDevices,
        auditLog: updatedAuditLog,
      };

      setRechargeStatus("Pushing to cloud…");
      await pushBackupDoc(currentRoomId, updatedBackupData, {
        deviceName,
        deviceId,
        lastAction: "record_recharge_web",
        lastActionByMember: payer.name,
      });

      pingSentry({ roomId: currentRoomId, payerName: payer.name, amount, deviceId, deviceName, ip });
      saveMemberId(room.id, payer.id);

      currentBackupData = updatedBackupData;
      renderRoom(updatedBackupData);
      renderRechargeForm(updatedBackupData);
      rechargeAmountInput.value = "";
      rechargeNoteInput.value = "";
      setRechargeStatus("Recharge logged.");
    } catch (err) {
      setRechargeStatus(err.message || "Couldn't log the recharge.", true);
    } finally {
      setRechargeBusy(false);
    }
  }

  /** Scope of the doc that matters for "did anything change under me" — excludes
   *  exportedAt/devices/auditLog since those churn on ordinary background syncs. */
  function comparableSnapshot(backupData) {
    return JSON.stringify({
      rooms: backupData.rooms || [],
      members: backupData.members || [],
      entries: backupData.entries || [],
      settings: backupData.settings || null,
    });
  }

  // ── Audit trail (existing AuditEvent/RoomDevice fields only, no schema changes) ──

  function upsertDevice(existing, { deviceId, deviceName, memberId, memberName, nowMs }) {
    const others = existing.filter((d) => d.deviceId !== deviceId);
    return [
      ...others,
      { deviceId, deviceName, memberId: memberId || null, memberName: memberName || null, lastSeenAtEpochMs: nowMs },
    ];
  }

  function appendAudit(existing, { action, deviceId, deviceName, memberId, memberName, entryId, groupId, detail, nowMs }) {
    const event = {
      id: crypto.randomUUID(),
      action,
      atEpochMs: nowMs,
      deviceId: deviceId || "",
      deviceName: deviceName || "",
      memberId: memberId || null,
      memberName: memberName || null,
      entryId: entryId || null,
      groupId: groupId || null,
      detail: detail || "",
    };
    return [...existing, event].slice(-MAX_AUDIT_EVENTS);
  }

  function buildAuditDetail({ payerName, amount, note, ip, deviceId }) {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "unknown";
    const parts = [
      `${payerName} ${amount}`,
      note ? `note="${note}"` : null,
      `ip=${ip || "unknown"}`,
      `ua=${navigator.userAgent}`,
      `lang=${navigator.language || "unknown"}`,
      `tz=${tz}`,
      `screen=${screen.width}x${screen.height}`,
      `device=${deviceId}`,
    ].filter(Boolean);
    return parts.join("; ").slice(0, 900);
  }

  function getOrCreateDeviceId() {
    const key = "splitbill_web_device_id";
    let id = localStorage.getItem(key);
    if (!id) {
      id = `web-${crypto.randomUUID()}`;
      localStorage.setItem(key, id);
    }
    return id;
  }

  function describeDevice() {
    const ua = navigator.userAgent;
    let browser = "Browser";
    if (/Edg\//.test(ua)) browser = "Edge";
    else if (/Chrome\//.test(ua)) browser = "Chrome";
    else if (/Firefox\//.test(ua)) browser = "Firefox";
    else if (/Safari\//.test(ua)) browser = "Safari";

    let os = "Unknown OS";
    if (/Windows/.test(ua)) os = "Windows";
    else if (/Mac OS X/.test(ua)) os = "macOS";
    else if (/Android/.test(ua)) os = "Android";
    else if (/iPhone|iPad|iPod/.test(ua)) os = "iOS";
    else if (/Linux/.test(ua)) os = "Linux";

    return `Web · ${browser} on ${os}`;
  }

  async function fetchPublicIp() {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 3000);
      const res = await fetch("https://api.ipify.org?format=json", { signal: controller.signal, cache: "no-store" });
      clearTimeout(timeout);
      if (!res.ok) return null;
      const json = await res.json();
      return json.ip || null;
    } catch {
      return null;
    }
  }

  // ── Sentry ping (raw envelope POST, no SDK) ─────────────

  function pingSentry({ roomId, payerName, amount, deviceId, deviceName, ip }) {
    try {
      const dsnMatch = SENTRY_DSN.match(/^https:\/\/([^@]+)@([^/]+)\/(.+)$/);
      if (!dsnMatch) return;
      const [, publicKey, host, projectId] = dsnMatch;
      const eventId = crypto.randomUUID().replace(/-/g, "");
      const nowIso = new Date().toISOString();

      const event = {
        event_id: eventId,
        timestamp: nowIso,
        platform: "javascript",
        level: "info",
        logger: "splitbill.web",
        message: { formatted: "Recharge logged via web room viewer" },
        environment: "web",
        tags: { source: "room-viewer", roomId },
        extra: {
          payerName,
          amount,
          deviceId,
          deviceName,
          ip: ip || "unknown",
          userAgent: navigator.userAgent,
          language: navigator.language,
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          screen: `${screen.width}x${screen.height}`,
        },
        request: { url: location.href, headers: { "User-Agent": navigator.userAgent } },
      };

      const envelope = [
        JSON.stringify({ event_id: eventId, sent_at: nowIso, dsn: SENTRY_DSN }),
        JSON.stringify({ type: "event" }),
        JSON.stringify(event),
      ].join("\n");

      const url = `https://${host}/api/${projectId}/envelope/?sentry_key=${publicKey}&sentry_version=7`;
      const blob = new Blob([envelope], { type: "application/x-sentry-envelope" });
      // fetch (not sendBeacon) so a failed/blocked send is visible in the console —
      // sendBeacon only reports "queued", never delivery, which made this silent to debug.
      fetch(url, { method: "POST", body: blob, keepalive: true, mode: "cors" })
        .then((res) => {
          if (!res.ok) {
            console.warn(`[SplitBill] Sentry ping rejected: ${res.status} ${res.statusText}`);
          }
        })
        .catch((err) => {
          // Most likely cause: an ad blocker / tracking-protection list blocking sentry.io.
          console.warn("[SplitBill] Sentry ping failed to send (possibly blocked by browser/extension):", err);
        });
    } catch (err) {
      console.warn("[SplitBill] Sentry ping threw before sending:", err);
    }
  }
})();
