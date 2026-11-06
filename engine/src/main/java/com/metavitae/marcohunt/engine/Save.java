package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Types.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Camp save (founder's "camp"): the whole game as plain text lines "key=value", and back.
 * Includes the random generator, so a loaded game continues exactly as it would have.
 * Holds no personal data: only squares, items, turns and the AI's notes.
 */
public final class Save {
    private Save() {}

    // ------------------------------------------------------------------ writing

    public static String write(Game g, Ai ai) {
        StringBuilder b = new StringBuilder("marcohunt-save=1\n");
        try {
            for (Field f : Rules.class.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                b.append("rules.").append(f.getName()).append('=').append(f.get(g.rules)).append('\n');
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        put(b, "rng", g.rng.state());
        put(b, "content", ords(g.content)); put(b, "opened", bools(g.opened)); put(b, "cast", bools(g.castByMarco));
        put(b, "reserved", bools(g.reserved));
        put(b, "spotlight", g.spotlight); put(b, "oracle", g.oracle); put(b, "oracleTask", g.oracleTaskItem.ordinal());
        put(b, "landmarks", flat(g.landmarks)); put(b, "marks", flat(g.marks));
        put(b, "round", g.round); put(b, "turn", g.turnIndex); put(b, "over", g.over); put(b, "winBy", g.winBy.ordinal());
        put(b, "finder", g.finder == null ? -1 : g.finder.id); put(b, "revealed", g.marcoRevealed); put(b, "oracleKnown", g.oracleKnown);
        StringBuilder e = new StringBuilder();
        for (Game.Echo x : g.echoes) { if (e.length() > 0) e.append(','); e.append(x.start).append(',').append(x.end).append(',').append(x.decoyId); }
        put(b, "echoes", e.toString());
        put(b, "asked", bools(g.teamAskedOracle));
        put(b, "moveRoll", g.moveRoll); put(b, "moveExtra", g.moveExtra); put(b, "budget", g.budget); put(b, "used", g.used);
        put(b, "together", g.together); put(b, "moved", g.moved); put(b, "lastCast", g.lastCastRound);
        put(b, "stats", ints(g.fights, g.wrongAccusations, g.echoesSet, g.echoesDetected, g.perilsHit, g.perilsBlocked,
            g.oracleAnswers, g.firstOracleRound, g.revealRound, g.casts, g.trades, g.oracleFoundRound, g.totalSteps));
        StringBuilder sr = new StringBuilder();
        for (int v : g.stepsByRound) { if (sr.length() > 0) sr.append(','); sr.append(v); }
        put(b, "stepsByRound", sr.toString());
        put(b, "trapsMarco", ints(g.trapsFromMarco)); put(b, "trapsLuck", ints(g.trapsFromLuck));
        put(b, "trail", flat(g.marcoTrail.toArray(new int[0][])));
        for (Player p : g.players) {
            String k = "p." + p.id + ".";
            put(b, k + "fixed", p.cls.ordinal() + "," + p.team + "," + (p.isMarco ? 1 : 0));
            put(b, k + "kind", p.kind == null ? -1 : p.kind.ordinal()); put(b, k + "human", p.human);
            put(b, k + "pos", ints(p.r, p.c, p.prevR, p.prevC));
            put(b, k + "state", ints(p.vigor, p.skillReadyAt, p.frozenTurns, p.hiddenSignals, p.shieldUp ? 1 : 0, p.accused ? 1 : 0,
                p.bonusOpp, p.stepsWalked, p.echoesStarted, p.lastEchoTurn));
            put(b, k + "carry", p.carry);
            put(b, k + "items", ords(p.items.toArray(new Content[0]))); put(b, k + "hand", ords(p.hand.toArray(new Content[0])));
        }
        if (ai != null) {
            put(b, "ai.cunning", ai.cunning);
            for (int i = 0; i < ai.brains.length; i++) {
                Ai.Brain br = ai.brains[i]; String k = "ai." + i + ".";
                put(b, k + "inc", ints(br.inc)); put(b, k + "hard", ints(br.hard)); put(b, k + "not", bools(br.notMarco));
                put(b, k + "nums", ints(br.obs, br.known, br.lastSingle, br.singleStreak, br.oracleWait));
                put(b, k + "cand", br.lastCand == null ? "-" : bools(br.lastCand));
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ reading

    /** Rebuilds the game. Returns {game, ai} (the ai is new and listening, with its saved notes). */
    public static Object[] read(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) { int i = line.indexOf('='); if (i > 0) m.put(line.substring(0, i), line.substring(i + 1)); }
        if (!"1".equals(m.get("marcohunt-save"))) throw new IllegalArgumentException("not a Marco Hunt save");
        Rules r = new Rules();
        try {
            for (Field f : Rules.class.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String v = m.get("rules." + f.getName()); if (v == null) continue;
                Class<?> t = f.getType();
                if (t == int.class) f.setInt(r, Integer.parseInt(v));
                else if (t == double.class) f.setDouble(r, Double.parseDouble(v));
                else if (t == boolean.class) f.setBoolean(r, Boolean.parseBoolean(v));
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        int np = r.players;
        Cls[] cls = new Cls[np];
        for (int i = 0; i < np; i++) cls[i] = Cls.values()[ia(m.get("p." + i + ".fixed"))[0]];
        Game g = new Game(r, 1, cls);
        // rebuild the players with their saved teams and Marco
        g.players.clear();
        for (int i = 0; i < np; i++) {
            String k = "p." + i + ".";
            int[] fx = ia(m.get(k + "fixed"));
            int[] st = ia(m.get(k + "state"));
            Player p = new Player(i, Cls.values()[fx[0]], fx[1], fx[2] == 1, st[0]);
            int kind = Integer.parseInt(m.get(k + "kind")); p.kind = kind < 0 ? null : Kind.values()[kind];
            p.human = Boolean.parseBoolean(m.get(k + "human"));
            int[] pos = ia(m.get(k + "pos")); p.r = pos[0]; p.c = pos[1]; p.prevR = pos[2]; p.prevC = pos[3];
            p.skillReadyAt = st[1]; p.frozenTurns = st[2]; p.hiddenSignals = st[3]; p.shieldUp = st[4] == 1; p.accused = st[5] == 1;
            p.bonusOpp = st[6]; p.stepsWalked = st[7]; p.echoesStarted = st[8]; p.lastEchoTurn = st[9];
            p.carry = Double.parseDouble(m.get(k + "carry"));
            for (int o : ia(m.get(k + "items"))) p.items.add(Content.values()[o]);
            for (int o : ia(m.get(k + "hand"))) p.hand.add(Content.values()[o]);
            g.players.add(p);
            if (p.isMarco) g.marco = p;
        }
        int[] ct = ia(m.get("content"));
        for (int i = 0; i < ct.length; i++) g.content[i] = Content.values()[ct[i]];
        ba(m.get("opened"), g.opened); ba(m.get("cast"), g.castByMarco);
        g.reserved = new boolean[g.n * g.n]; ba(m.get("reserved"), g.reserved);
        g.spotlight = Integer.parseInt(m.get("spotlight")); g.oracle = Integer.parseInt(m.get("oracle"));
        g.oracleTaskItem = Content.values()[Integer.parseInt(m.get("oracleTask"))];
        g.landmarks = unflat(ia(m.get("landmarks")), 2); g.marks = unflat(ia(m.get("marks")), 4);
        g.round = Integer.parseInt(m.get("round")); g.turnIndex = Integer.parseInt(m.get("turn"));
        g.over = Boolean.parseBoolean(m.get("over")); g.winBy = WinBy.values()[Integer.parseInt(m.get("winBy"))];
        int fi = Integer.parseInt(m.get("finder")); g.finder = fi < 0 ? null : g.players.get(fi);
        g.marcoRevealed = Boolean.parseBoolean(m.get("revealed")); g.oracleKnown = Boolean.parseBoolean(m.get("oracleKnown"));
        g.echoes.clear();
        int[] ec = ia(m.get("echoes"));
        for (int i = 0; i + 2 < ec.length; i += 3) g.echoes.add(new Game.Echo(ec[i], ec[i + 1], ec[i + 2]));
        ba(m.get("asked"), g.teamAskedOracle);
        g.moveRoll = Integer.parseInt(m.get("moveRoll")); g.moveExtra = Integer.parseInt(m.get("moveExtra"));
        g.budget = Double.parseDouble(m.get("budget")); g.used = Integer.parseInt(m.get("used"));
        g.together = Boolean.parseBoolean(m.get("together")); g.moved = Boolean.parseBoolean(m.get("moved"));
        g.lastCastRound = Integer.parseInt(m.get("lastCast"));
        int[] s = ia(m.get("stats"));
        g.fights = s[0]; g.wrongAccusations = s[1]; g.echoesSet = s[2]; g.echoesDetected = s[3]; g.perilsHit = s[4]; g.perilsBlocked = s[5];
        g.oracleAnswers = s[6]; g.firstOracleRound = s[7]; g.revealRound = s[8]; g.casts = s[9]; g.trades = s[10]; g.oracleFoundRound = s[11]; g.totalSteps = s[12];
        g.stepsByRound.clear(); for (int v : ia(m.get("stepsByRound"))) g.stepsByRound.add(v);
        g.trapsFromMarco = ia(m.get("trapsMarco")); g.trapsFromLuck = ia(m.get("trapsLuck"));
        if (g.trapsFromMarco.length < np) g.trapsFromMarco = new int[np];
        if (g.trapsFromLuck.length < np) g.trapsFromLuck = new int[np];
        g.marcoTrail.clear(); for (int[] t : unflat(ia(m.get("trail")), 2)) g.marcoTrail.add(t);
        g.rng.setState(Long.parseLong(m.get("rng")));
        Ai ai = new Ai(g);
        if (m.containsKey("ai.cunning")) {
            ai.cunning = Double.parseDouble(m.get("ai.cunning"));
            for (int i = 0; i < ai.brains.length; i++) {
                Ai.Brain br = ai.brains[i]; String k = "ai." + i + ".";
                if (!m.containsKey(k + "inc")) continue;
                int[] inc = ia(m.get(k + "inc")), hard = ia(m.get(k + "hard"));
                System.arraycopy(inc, 0, br.inc, 0, Math.min(inc.length, br.inc.length));
                System.arraycopy(hard, 0, br.hard, 0, Math.min(hard.length, br.hard.length));
                ba(m.get(k + "not"), br.notMarco);
                int[] nums = ia(m.get(k + "nums"));
                br.obs = nums[0]; br.known = nums[1]; br.lastSingle = nums[2]; br.singleStreak = nums[3]; br.oracleWait = nums[4];
                String cand = m.get(k + "cand");
                if (cand != null && !cand.equals("-")) { br.lastCand = new boolean[np]; ba(cand, br.lastCand); } else br.lastCand = null;
            }
        }
        return new Object[]{g, ai};
    }

    // ------------------------------------------------------------------ helpers

    private static void put(StringBuilder b, String k, Object v) { b.append(k).append('=').append(v).append('\n'); }
    private static String ints(int... a) { StringBuilder s = new StringBuilder(); for (int v : a) { if (s.length() > 0) s.append(','); s.append(v); } return s.toString(); }
    private static String bools(boolean[] a) { StringBuilder s = new StringBuilder(); for (boolean v : a) s.append(v ? '1' : '0'); return s.toString(); }
    private static String ords(Content[] a) { int[] o = new int[a.length]; for (int i = 0; i < a.length; i++) o[i] = a[i].ordinal(); return ints(o); }
    private static String flat(int[][] a) { List<Integer> l = new ArrayList<>(); for (int[] r : a) for (int v : r) l.add(v); int[] o = new int[l.size()]; for (int i = 0; i < o.length; i++) o[i] = l.get(i); return ints(o); }
    private static int[] ia(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] p = s.split(","); int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) o[i] = Integer.parseInt(p[i].trim());
        return o;
    }
    private static void ba(String s, boolean[] into) { if (s == null) return; for (int i = 0; i < s.length() && i < into.length; i++) into[i] = s.charAt(i) == '1'; }
    private static int[][] unflat(int[] a, int w) { int[][] o = new int[a.length / w][]; for (int i = 0; i < o.length; i++) { o[i] = new int[w]; System.arraycopy(a, i * w, o[i], 0, w); } return o; }
}
