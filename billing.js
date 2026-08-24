// Faithful JS port of app/src/main/java/com/anant/splitbill/data/repository/BillEngine.kt
// Keep this in lockstep with that file — it's the source of truth for the math.
window.SplitBillEngine = (() => {
  "use strict";

  function round2(n) {
    const scaled = n * 100;
    const rounded = scaled >= 0 ? Math.floor(scaled + 0.5) : Math.ceil(scaled - 0.5);
    return rounded / 100;
  }

  function emptyState(members) {
    const ordered = [...members].sort((a, b) => a.sortOrder - b.sortOrder);
    return {
      members: ordered.map((m) => ({
        memberId: m.id,
        name: m.name,
        balance: 0,
        lastReading: 0,
        lastReadingBeforeRecharge: 0,
      })),
      lastRechargeAmount: 0,
      lastRechargeMemberId: null,
      lastRechargeMemberName: null,
    };
  }

  function byId(state) {
    const map = new Map();
    for (const m of state.members) map.set(m.memberId, m);
    return map;
  }

  function withMembers(state, membersList) {
    return { ...state, members: membersList };
  }

  function balancesString(state, currency = "Rs.") {
    return state.members.map((m) => `${m.name}: ${currency}${m.balance.toFixed(2)}`).join("; ");
  }

  function parseBalances(snapshot, template) {
    const byName = new Map(template.members.map((m) => [m.name, m.memberId]));
    const out = new Map(template.members.map((m) => [m.memberId, m.balance]));
    for (const partRaw of snapshot.split(";")) {
      const p = partRaw.trim();
      if (!p.includes(": Rs.") && !p.includes(":Rs.")) continue;
      const sep = p.includes(": Rs.") ? ": Rs." : ":Rs.";
      const idx = p.indexOf(sep);
      if (idx < 0) continue;
      const name = p.slice(0, idx).trim();
      const amountStr = p.slice(idx + sep.length).trim().replace(/,/g, "");
      const id = byName.get(name);
      if (!id) continue;
      const amount = Number(amountStr);
      if (Number.isFinite(amount)) out.set(id, round2(amount));
    }
    return out;
  }

  function withBalances(state, balances) {
    return withMembers(
      state,
      state.members.map((m) => ({ ...m, balance: balances.has(m.memberId) ? balances.get(m.memberId) : m.balance }))
    );
  }

  function deductPreviousRecharge(state) {
    if (state.members.every((m) => m.lastReadingBeforeRecharge === 0)) return state;

    const consumption = new Map(
      state.members.map((m) => [m.memberId, Math.max(0, m.lastReading - m.lastReadingBeforeRecharge)])
    );
    const total = [...consumption.values()].reduce((a, b) => a + b, 0);
    if (total <= 0 || state.lastRechargeAmount <= 0) return state;

    const updated = state.members.map((m) => {
      const ratio = consumption.get(m.memberId) / total;
      const deduction = round2(state.lastRechargeAmount * ratio);
      return { ...m, balance: round2(m.balance - deduction) };
    });
    return withMembers(state, updated);
  }

  /** Mirrors BillEngine.recordReadingsAndRecharge — returns { entries, state }. */
  function recordReadingsAndRecharge({
    roomId,
    members,
    current,
    readings,
    rechargeMemberId,
    rechargeAmount,
    nowEpochMs,
    groupId,
    loggedByMemberId = null,
    loggedByMemberName = null,
    loggedByDeviceId = null,
  }) {
    const ordered = [...members].sort((a, b) => a.sortOrder - b.sortOrder);
    if (ordered.length === 0) throw new Error("Add at least one member");

    const stateMap = byId(current);
    for (const m of ordered) {
      const newVal = readings[m.id];
      if (newVal === undefined || newVal === null || Number.isNaN(newVal)) {
        throw new Error(`Missing reading for ${m.name}`);
      }
      const prev = stateMap.get(m.id).lastReading;
      if (newVal < prev) {
        throw new Error(`New reading for ${m.name} (${newVal}) cannot be less than previous (${prev})`);
      }
    }

    const out = [];
    let working = current;
    const runningMap = new Map(stateMap);

    for (const m of ordered) {
      const newVal = readings[m.id];
      const prev = runningMap.get(m.id).lastReading;
      const consumption = prev <= 0 ? 0 : newVal - prev;
      runningMap.set(m.id, { ...runningMap.get(m.id), lastReading: newVal });
      working = withMembers(working, ordered.map((o) => runningMap.get(o.id)));
      out.push({
        id: crypto.randomUUID(),
        roomId,
        type: "READING",
        memberId: m.id,
        memberName: m.name,
        loggedByMemberId,
        loggedByMemberName,
        loggedByDeviceId,
        value: newVal,
        consumption,
        note: "",
        timestampEpochMs: nowEpochMs,
        groupId,
        balancesSnapshot: balancesString(working),
        deleted: false,
      });
    }

    working = deductPreviousRecharge(working);
    const afterDeduct = byId(working);

    if (rechargeAmount > 0) {
      const payer = ordered.find((m) => m.id === rechargeMemberId);
      if (!payer) throw new Error("Unknown recharge member");
      const credited = round2(afterDeduct.get(payer.id).balance + rechargeAmount);
      const updatedAfter = new Map(afterDeduct);
      updatedAfter.set(payer.id, { ...updatedAfter.get(payer.id), balance: credited });
      for (const m of ordered) {
        updatedAfter.set(m.id, { ...updatedAfter.get(m.id), lastReadingBeforeRecharge: updatedAfter.get(m.id).lastReading });
      }
      working = {
        ...withMembers(working, ordered.map((o) => updatedAfter.get(o.id))),
        lastRechargeAmount: rechargeAmount,
        lastRechargeMemberId: payer.id,
        lastRechargeMemberName: payer.name,
      };
      out.push({
        id: crypto.randomUUID(),
        roomId,
        type: "RECHARGE",
        memberId: payer.id,
        memberName: payer.name,
        loggedByMemberId,
        loggedByMemberName,
        loggedByDeviceId,
        value: rechargeAmount,
        consumption: null,
        note: "",
        timestampEpochMs: nowEpochMs + 1000,
        groupId,
        balancesSnapshot: balancesString(working),
        deleted: false,
      });
    } else {
      working = withMembers(working, ordered.map((o) => afterDeduct.get(o.id)));
    }

    return { entries: out, state: working };
  }

  function recordExpense({ roomId, members, current, payerId, amount, note = "", nowEpochMs, groupId, loggedByMemberId = null, loggedByMemberName = null }) {
    if (!(amount > 0)) throw new Error("Expense amount must be positive");
    const ordered = [...members].sort((a, b) => a.sortOrder - b.sortOrder);
    const payer = ordered.find((m) => m.id === payerId);
    if (!payer) throw new Error("Unknown payer");
    const share = round2(amount / ordered.length);
    const map = byId(current);
    const updated = new Map(map);
    for (const m of ordered) {
      updated.set(m.id, { ...updated.get(m.id), balance: round2(updated.get(m.id).balance - share) });
    }
    updated.set(payer.id, { ...updated.get(payer.id), balance: round2(updated.get(payer.id).balance + amount) });
    const working = withMembers(current, ordered.map((o) => updated.get(o.id)));
    const entry = {
      id: crypto.randomUUID(),
      roomId,
      type: "EXPENSE",
      memberId: payer.id,
      memberName: payer.name,
      loggedByMemberId,
      loggedByMemberName,
      value: amount,
      consumption: null,
      note,
      timestampEpochMs: nowEpochMs,
      groupId,
      balancesSnapshot: balancesString(working),
      deleted: false,
    };
    return { entries: [entry], state: working };
  }

  /** Mirrors BillEngine.rebuild — replay all non-deleted entries in group order. */
  function rebuild(members, entries) {
    const ordered = [...members].sort((a, b) => a.sortOrder - b.sortOrder);
    let state = emptyState(ordered);
    const active = entries.filter((e) => !e.deleted);
    if (active.length === 0) return state;

    const groupsMap = new Map();
    for (const e of active) {
      if (!groupsMap.has(e.groupId)) groupsMap.set(e.groupId, []);
      groupsMap.get(e.groupId).push(e);
    }
    const groups = [...groupsMap.entries()].sort(
      (a, b) => Math.min(...a[1].map((e) => e.timestampEpochMs)) - Math.min(...b[1].map((e) => e.timestampEpochMs))
    );

    for (const [, groupEntries] of groups) {
      const readings = groupEntries.filter((e) => e.type === "READING");
      const recharge = groupEntries.find((e) => e.type === "RECHARGE");
      const expense = groupEntries.find((e) => e.type === "EXPENSE");

      if (readings.length > 0) {
        const readingMap = {};
        for (const r of readings) {
          if (r.memberId) readingMap[r.memberId] = r.value;
        }
        if (Object.keys(readingMap).length !== ordered.length) {
          const withSnap = [...groupEntries].reverse().find((e) => e.balancesSnapshot && e.balancesSnapshot.trim());
          if (withSnap) {
            state = withBalances(state, parseBalances(withSnap.balancesSnapshot, state));
            const map = byId(state);
            for (const r of readings) {
              if (r.memberId && map.has(r.memberId)) {
                map.set(r.memberId, { ...map.get(r.memberId), lastReading: r.value });
              }
            }
            state = withMembers(state, ordered.map((o) => map.get(o.id)));
            if (recharge) {
              state = {
                ...state,
                lastRechargeAmount: recharge.value,
                lastRechargeMemberId: recharge.memberId,
                lastRechargeMemberName: recharge.memberName,
              };
            }
          }
          continue;
        }
        const result = recordReadingsAndRecharge({
          roomId: readings[0].roomId,
          members: ordered,
          current: state,
          readings: readingMap,
          rechargeMemberId: recharge?.memberId || "",
          rechargeAmount: recharge?.value || 0,
          nowEpochMs: Math.min(...readings.map((r) => r.timestampEpochMs)),
          groupId: groupEntries[0].groupId,
        });
        state = result.state;
      } else if (expense) {
        if (!expense.memberId) continue;
        const result = recordExpense({
          roomId: expense.roomId,
          members: ordered,
          current: state,
          payerId: expense.memberId,
          amount: expense.value,
          note: expense.note,
          nowEpochMs: expense.timestampEpochMs,
          groupId: expense.groupId,
        });
        state = result.state;
      }
    }
    return state;
  }

  return { round2, emptyState, rebuild, recordReadingsAndRecharge, recordExpense, deductPreviousRecharge, balancesString, parseBalances };
})();
