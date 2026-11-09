package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Types.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI players: AI Marco and AI travellers (Wanderer, Follower, Detective, Fighter).
 * Each AI only uses what that player could know: public state plus its own private news.
 * The solo game and the simulation both use this class.
 */
public final class Ai implements Game.Listener {

    /** What one AI traveller believes about who Marco is. */
    static final class Brain {
        final int[] inc;      // inconsistencies from signals, falcon, compass, divination (cleared by a Spyglass that finds an echo)
        final int[] hard;     // inconsistencies from the Oracle (never cleared)
        final boolean[] notMarco;
        int obs = 0;
        int known = -1;       // Marco's id once revealed
        boolean[] lastCand;   // consistent with the latest signal
        int lastSingle = -1, singleStreak = 0;
        int oracleWait = 0;   // turns spent waiting on the Oracle square for the team
        boolean[] belief; int beliefRound;
        Brain(int np) { inc = new int[np]; hard = new int[np]; notMarco = new boolean[np]; }
    }

    private final Game g;
    final Brain[] brains;
    /** Cunning 0..1 (difficulty): chance that an AI traveller uses the clever move instead of a plain one. */
    public double cunning = 1.0;
    /** With a trail signal: true = remembers where everyone was (a perfect note-taker); false = compares with where they are now, as most people would. */
    public boolean perfectMemory = true;
    /** Hidden Marco: how often a rival follows its notes instead of wandering (players other than seat 0); below 0 = cunning. */
    public double rivalSkill = -1;

    public Ai(Game g) {
        this.g = g;
        brains = new Brain[g.players.size()];
        for (Player p : g.players) brains[p.id] = new Brain(g.players.size());
        g.listeners.add(this);
    }

    // ------------------------------------------------------------------ listening

    // ---- hidden Marco: each AI keeps the set of squares he could be on, as a careful player would
    private boolean[] belief(Player p) {
        Brain b = brains[p.id];
        if (b.belief == null) { b.belief = new boolean[g.n * g.n]; Arrays.fill(b.belief, true); b.beliefRound = g.round; }
        return b.belief;
    }

    /** He has moved since we last looked: every believed square spreads by the die. */
    private void spread(Player p) {
        Brain b = brains[p.id];
        boolean[] s = belief(p);
        int radius = (g.round - b.beliefRound) * (g.rules.marcoStep > 0 ? g.rules.marcoStep : g.rules.dieMax());
        if (radius <= 0) return;
        boolean[] out = new boolean[s.length];
        for (int i = 0; i < s.length; i++) {
            if (!s[i]) continue;
            int r0 = i / g.n, c0 = i % g.n;
            for (int r = Math.max(0, r0 - radius); r <= Math.min(g.n - 1, r0 + radius); r++)
                for (int c = Math.max(0, c0 - radius); c <= Math.min(g.n - 1, c0 + radius); c++)
                    if (Math.abs(r - r0) + Math.abs(c - c0) <= radius) out[r * g.n + c] = true;
        }
        b.belief = out; b.beliefRound = g.round;
    }

    private interface SquareTest { boolean ok(int r, int c); }

    private void narrow(Player p, SquareTest t) {
        boolean[] s = belief(p);
        boolean[] kept = new boolean[s.length];
        boolean any = false;
        for (int i = 0; i < s.length; i++) if (s[i] && t.ok(i / g.n, i % g.n)) { kept[i] = true; any = true; }
        if (!any) for (int i = 0; i < s.length; i++) if (t.ok(i / g.n, i % g.n)) kept[i] = true; // the notes contradict themselves: a lie; start again
        brains[p.id].belief = kept;
    }

    /** The believed square nearest to the player (ties at random). */
    private int[] believedTarget(Player p) {
        boolean[] s = belief(p);
        int best = -1; double bestD = Double.MAX_VALUE;
        for (int i = 0; i < s.length; i++) {
            if (!s[i] || i == g.idx(p.r, p.c)) continue;
            double d = p.dist(i / g.n, i % g.n) + g.rng.nextDouble() * 2;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best < 0 ? new int[]{g.n / 2, g.n / 2} : new int[]{best / g.n, best % g.n};
    }

    @Override public void onSignal(Game g, Player p, Band band) {
        Brain b = brains[p.id];
        if (g.rules.hiddenMarco) { b.obs++; spread(p); narrow(p, (r, c) -> g.reading(p.cls, p.dist(r, c)) == band); return; }
        b.obs++;
        boolean[] cand = new boolean[g.players.size()];
        int count = 0, single = -1;
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            int[] at = perfectMemory ? g.pastPos(s, g.rules.signalLag) : new int[]{s.r, s.c};
            boolean ok = g.reading(p.cls, p.dist(at[0], at[1])) == band;
            if (!ok) b.inc[s.id]++;
            else { cand[s.id] = true; count++; single = s.id; }
        }
        b.lastCand = cand;
        if (count == 1) { b.singleStreak = (single == b.lastSingle) ? b.singleStreak + 1 : 1; b.lastSingle = single; }
        else { b.singleStreak = 0; b.lastSingle = -1; }
    }

    @Override public void onFalcon(Game g, Player p, Dir d) {
        Brain b = brains[p.id];
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            int dr = s.r - p.r, dc = s.c - p.c;
            boolean ok;
            boolean ns = Math.abs(dr) >= Math.abs(dc) && dr != 0, ew = Math.abs(dc) >= Math.abs(dr) && dc != 0;
            switch (d) {
                case N: ok = ns && dr < 0; break;
                case S: ok = ns && dr > 0; break;
                case E: ok = ew && dc > 0; break;
                default: ok = ew && dc < 0; break;
            }
            if (!ok) b.inc[s.id]++;
        }
    }

    @Override public void onCompass(Game g, Player p, boolean rows, int v) {
        Brain b = brains[p.id];
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            int got = rows ? Math.abs(s.r - p.r) : Math.abs(s.c - p.c);
            if (got != v) b.inc[s.id]++;
        }
    }

    @Override public void onDivination(Game g, Player p, int ar, int ac, int br, int bc, int ans) {
        Brain b = brains[p.id];
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            if (Integer.compare(s.dist(ar, ac), s.dist(br, bc)) != ans) b.inc[s.id]++;
        }
    }

    @Override public void onSpyglass(Game g, Player p, boolean found) {
        if (!found) return;
        Brain b = brains[p.id];
        java.util.Arrays.fill(b.inc, 0);
        b.obs = 0; b.singleStreak = 0; b.lastSingle = -1; b.lastCand = null;
    }

    @Override public void onOracle(Game g, Player p, int mark, int about) {
        if (g.rules.hiddenMarco) {
            int[] m = g.marks[mark];
            spread(p);
            narrow(p, (r, c) -> Math.abs(Math.abs(r - m[0]) + Math.abs(c - m[1]) - about) <= g.rules.oracleTolerance);
            return;
        }
        Brain b = brains[p.id];
        int[] M = g.marks[mark];
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            if (Math.abs(s.dist(M[0], M[1]) - about) > g.rules.oracleTolerance) b.hard[s.id]++;
        }
    }

    @Override public void onNotMarco(Game g, Player q) { for (Brain b : brains) b.notMarco[q.id] = true; }
    @Override public void onWrongAccusation(Game g, Player a, Player q) { brains[a.id].notMarco[q.id] = true; }
    @Override public void onReveal(Game g, Player m) { for (Brain b : brains) b.known = m.id; }

    // ------------------------------------------------------------------ beliefs

    /** The suspects with the fewest contradictions (never the player himself or a proven non-Marco). */
    List<Player> candidates(Player p) {
        Brain b = brains[p.id];
        List<Player> out = new ArrayList<>();
        if (b.known >= 0) { out.add(g.players.get(b.known)); return out; }
        int best = Integer.MAX_VALUE;
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            int sc = b.hard[s.id] * 1000 + b.inc[s.id];
            if (sc < best) { best = sc; out.clear(); }
            if (sc == best) out.add(s);
        }
        return out;
    }

    List<Player> followerCandidates(Player p) {
        Brain b = brains[p.id];
        List<Player> out = new ArrayList<>();
        if (b.known >= 0) { out.add(g.players.get(b.known)); return out; }
        for (Player s : g.players) {
            if (s == p || b.notMarco[s.id]) continue;
            if (b.lastCand == null || b.lastCand[s.id]) out.add(s);
        }
        if (out.isEmpty()) for (Player s : g.players) if (s != p && !b.notMarco[s.id]) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------ playing a turn

    /** Plays the current player's whole turn (beginTurn must already have returned true). */
    public void playTurn(Player p) {
        if (p.isMarco) playMarco(p); else playHunter(p);
    }

    /** Convenience for the simulation: play until the game is over. */
    public void playToEnd() {
        while (!g.over) {
            Player p = g.current();
            if (g.beginTurn()) playTurn(p);
        }
    }

    private void shareItems(Player p) {
        for (Player q : g.at(p.r, p.c)) {
            if (!p.teammateOf(q)) continue;
            for (Content it : new ArrayList<>(p.items))
                if (!Types.suits(p.cls, it, g.rules.secondItems) && Types.suits(q.cls, it, g.rules.secondItems)) g.give(p, q, it);
        }
    }

    private boolean clever() { return cunning >= 1.0 || g.rng.nextDouble() < cunning; }

    private void playHunter(Player p) {
        Brain b = brains[p.id];
        shareItems(p);
        List<Player> cands;
        switch (p.kind == null ? Kind.DETECTIVE : p.kind) {
            case DETECTIVE: cands = candidates(p); break;
            case WANDERER: cands = new ArrayList<>(); break;
            default: cands = followerCandidates(p); break;
        }
        Kind k = p.kind == null ? Kind.DETECTIVE : p.kind;

        // 1. accuse when sure (after a Spyglass check if one is in the sac)
        if (k != Kind.WANDERER && g.rules.accusations && !g.marcoRevealed && !p.accused && clever()) {
            Player sure = null;
            if (k == Kind.DETECTIVE && cands.size() == 1 && (b.obs >= 2 || b.hard[cands.get(0).id] == 0 && g.oracleAnswers > 0)) sure = cands.get(0);
            if (k != Kind.DETECTIVE && b.singleStreak >= 2) sure = g.players.get(b.lastSingle);
            if (sure != null && !b.notMarco[sure.id]) {
                if (p.items.contains(Content.SPYGLASS) && g.opportunitiesLeft() > 0) {
                    g.useSpyglass(p);
                    if (b.obs == 0) sure = null; // an echo was found: start the notes again
                }
                if (sure != null) { g.accuse(p, sure); if (g.over) return; }
            }
        }

        // 2. information items and skills when unsure
        if (k != Kind.WANDERER && cands.size() >= 2 && clever()) {
            if (p.items.contains(Content.FALCON) && g.opportunitiesLeft() > 0) g.useFalcon(p);
            if (p.items.contains(Content.COMPASS) && g.opportunitiesLeft() > 0) g.useCompass(p);
            if (!g.rules.simple && p.cls == Cls.MAGUS && g.skillReady(p) && g.opportunitiesLeft() > 0) divine(p, cands);
            if (k == Kind.DETECTIVE) cands = candidates(p);
        }
        // a Spyglass when the notes contradict themselves (an echo is likely)
        if (k == Kind.DETECTIVE && p.items.contains(Content.SPYGLASS) && g.opportunitiesLeft() > 0 && b.known < 0) {
            boolean allContradicted = !cands.isEmpty() && b.inc[cands.get(0).id] > 0;
            if (allContradicted) { g.useSpyglass(p); cands = candidates(p); }
        }

        // 3. fighters attack within reach
        if (!g.rules.simple && k == Kind.FIGHTER && g.opportunitiesLeft() > 0) {
            List<Player> inReach = new ArrayList<>();
            for (Player q : g.players) if (q != p && !p.teammateOf(q) && p.dist(q) <= Types.reach(p.cls)) inReach.add(q);
            if (!inReach.isEmpty()) {
                if (p.cls == Cls.MONGOL && g.skillReady(p)) g.volley(p, inReach.get(0), inReach.size() > 1 ? inReach.get(1) : null);
                else g.attack(p, inReach.get(0));
                if (g.over) return;
            }
        }

        // 4. where to go
        int tr, tc;
        int[] errand = (k == Kind.WANDERER || k == Kind.FIGHTER || cands.size() == 1) ? null : nearestErrand(p);
        boolean oraclePlan = errand == null && !(g.rules.hiddenMarco && k == Kind.WANDERER) && wantsOracle(p, k, g.rules.hiddenMarco ? g.players : cands);
        if (errand != null) {
            tr = errand[0]; tc = errand[1];
        } else if (g.rules.hiddenMarco && k != Kind.WANDERER && !oraclePlan) {
            boolean follows = (p.id != 0 && rivalSkill >= 0) ? g.rng.nextDouble() < rivalSkill : clever();
            int[] t = follows ? believedTarget(p) : new int[]{g.rng.nextInt(g.n), g.rng.nextInt(g.n)};
            tr = t[0]; tc = t[1];
        } else if (oraclePlan) {
            tr = g.oracle / g.n; tc = g.oracle % g.n;
            if (p.r == tr && p.c == tc && b.oracleWait < 4) { b.oracleWait++; g.rest(p); g.endTurn(p); return; } // wait for the team, rest up
        } else if (k == Kind.WANDERER) {
            tr = g.rng.nextInt(g.n); tc = g.rng.nextInt(g.n);
        } else if (k == Kind.FIGHTER && b.known < 0) {
            Player t = nearestOther(p, null);
            tr = t.r; tc = t.c;
        } else if (!cands.isEmpty()) {
            Player t = nearest(p, cands);
            tr = t.r; tc = t.c;
        } else { tr = g.n / 2; tc = g.n / 2; }

        // Leap when the target is just out of reach
        if (p.items.contains(Content.LEAP) && g.opportunitiesLeft() > 0 && p.dist(tr, tc) > g.moveAllowance() && clever()) g.useLeap(p);

        int[] dest = chooseSquare(p, tr, tc, k, b);
        g.move(p, dest[0], dest[1]);
        if (g.over) return;
        g.endTurn(p);
    }

    private int[] nearestErrand(Player p) {
        int[] best = null;
        for (int k : g.errands.get(p.id)) {
            int[] L = g.landmarks[k];
            if (best == null || p.dist(L[0], L[1]) < p.dist(best[0], best[1])) best = L;
        }
        return best;
    }

    /** Marco with errands: heads for his next place like anyone on an errand, shying away only from hunters right beside him. */
    private void playErrandMarco(Player m) {
        int[] t = nearestErrand(m);
        int k0 = g.moveAllowance();
        int[] best = null; double bestScore = Double.MAX_VALUE;
        for (int r = Math.max(0, m.r - k0); r <= Math.min(g.n - 1, m.r + k0); r++)
            for (int c = Math.max(0, m.c - k0); c <= Math.min(g.n - 1, m.c + k0); c++) {
                if (!g.reachable(m, r, c, k0)) continue;
                if (!(r == m.r && c == m.c) && !g.at(r, c).isEmpty()) continue;
                int i = g.idx(r, c);
                if (g.content[i].isPeril() && !g.opened[i]) continue;
                double score = Math.abs(r - t[0]) + Math.abs(c - t[1]) + g.rng.nextDouble() * 0.5;
                for (Player q : g.players) {
                    if (q == m) continue;
                    int d = Math.abs(q.r - r) + Math.abs(q.c - c);
                    if (d <= 2) score += (3 - d) * 1.5;
                }
                if (score < bestScore) { bestScore = score; best = new int[]{r, c}; }
            }
        if (best == null) { g.rest(m); g.endTurn(m); return; }
        g.move(m, best[0], best[1]);
        if (g.over) return;
        g.endTurn(m);
    }

    private boolean wantsOracle(Player p, Kind k, List<Player> cands) {
        if (!g.oracleKnown || g.marcoRevealed) return false;
        if (g.rules.simple && g.teamAskedOracle[8 + p.id]) return false; // already heard it
        // a teammate is waiting on the Oracle square: everyone in the team comes
        for (Player q : g.players)
            if (p.teammateOf(q) && g.idx(q.r, q.c) == g.oracle && brains[p.id].oracleWait < 4) return true;
        if (k == Kind.WANDERER || k == Kind.FIGHTER) return false;
        if (cands.size() <= 1 || brains[p.id].oracleWait >= 4) return false;
        if (k == Kind.FOLLOWER && (p.id + g.round / 4) % 2 == 0) return false; // followers only sometimes bother
        return true;
    }

    private void divine(Player p, List<Player> cands) {
        Player a = cands.get(0), b = cands.get(1);
        int[] sa = emptyNeighbour(a), sb = emptyNeighbour(b);
        if (sa == null || sb == null || (sa[0] == sb[0] && sa[1] == sb[1])) return;
        g.divination(p, sa[0], sa[1], sb[0], sb[1]);
    }

    private int[] emptyNeighbour(Player s) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] x : d) {
            int r = s.r + x[0], c = s.c + x[1];
            if (g.onBoard(r, c) && g.at(r, c).isEmpty()) return new int[]{r, c};
        }
        return null;
    }

    private Player nearest(Player p, List<Player> list) {
        Player best = null;
        for (Player q : list) if (best == null || p.dist(q) < p.dist(best)) best = q;
        return best;
    }

    private Player nearestOther(Player p, Player skip) {
        Player best = null;
        for (Player q : g.players) {
            if (q == p || q == skip || p.teammateOf(q)) continue;
            if (best == null || p.dist(q) < p.dist(best)) best = q;
        }
        return best;
    }

    /** Best reachable square toward (tr,tc): land on it if possible, else get as close as possible. */
    private int[] chooseSquare(Player p, int tr, int tc, Kind k, Brain b) {
        int k0 = g.moveAllowance();
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = Math.max(0, p.r - k0); r <= Math.min(g.n - 1, p.r + k0); r++) {
            for (int c = Math.max(0, p.c - k0); c <= Math.min(g.n - 1, p.c + k0); c++) {
                if (!g.reachable(p, r, c, k0)) continue;
                double score = Math.abs(r - tr) + Math.abs(c - tc);
                int i = g.idx(r, c);
                if (g.unopenedDecorated(i)) score -= (k == Kind.WANDERER ? 0 : 0.4); // curious about decorations
                for (Player q : g.at(r, c)) {
                    if (q == p || p.teammateOf(q)) continue;
                    if (k != Kind.FIGHTER && b.notMarco[q.id] && !(r == tr && c == tc)) score += 1.5; // avoid pointless fights
                }
                score += g.rng.nextDouble() * 0.01;
                if (score < bestScore) { bestScore = score; best = new int[]{r, c}; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ AI Marco

    private void playMarco(Player m) {
        // cast one gift or trap into a free slot
        if (!m.hand.isEmpty()) castSomething(m);
        // keep an echo going
        if (g.rules.simple && g.canEcho(m) && !g.echoActive()) {
            // his one lie: kept for when a hunter comes close
            Player close = nearestOther(m, null);
            if (close != null && close.dist(m) <= 2 * g.rules.dieMax()) {
                Player decoy = null;
                for (Player q : g.players) if (q != m && (decoy == null || q.dist(m) > decoy.dist(m))) decoy = q;
                if (decoy != null) g.setEcho(m, decoy, 0, 1);
            }
        } else if (g.canEcho(m) && !g.echoActive()) {
            Player decoy = null;
            for (Player q : g.players) if (q != m && (decoy == null || q.dist(m) > decoy.dist(m))) decoy = q;
            if (decoy != null) g.setEcho(m, decoy, 0, 2 + g.rng.nextInt(Math.max(1, g.rules.echoMaxLength - 1)));
        }
        if (g.rules.marcoErrands > 0 && !g.errands.get(m.id).isEmpty()) { playErrandMarco(m); return; }
        if (g.rules.marcoAmbles) {
            g.forceRoll(Math.max(1, g.rules.marcoStep));
            List<int[]> ways = new ArrayList<>();
            if (g.rng.nextDouble() < g.rules.marcoMoveChance) {
                int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] x : d) {
                    int r = m.r + x[0], c = m.c + x[1];
                    if (!g.onBoard(r, c) || !g.at(r, c).isEmpty()) continue;
                    int i = g.idx(r, c);
                    if (g.content[i].isPeril() && !g.opened[i]) continue;
                    ways.add(new int[]{r, c});
                }
            }
            if (ways.isEmpty()) g.rest(m);
            else { int[] w = ways.get(g.rng.nextInt(ways.size())); g.move(m, w[0], w[1]); }
            if (g.over) return;
            g.endTurn(m);
            return;
        }
        if (g.rules.marcoStep > 0) g.forceRoll(g.rules.marcoStep);
        // flee: the reachable square farthest from the nearest non-teammate
        Player near = nearestOther(m, null);
        if (g.rules.marcoStep == 0 && near != null && m.items.contains(Content.LEAP) && g.opportunitiesLeft() > 0 && near.dist(m) <= g.rules.dieMax()) g.useLeap(m);
        int k0 = g.moveAllowance();
        int[] best = null;
        double bestScore = -1;
        for (int r = Math.max(0, m.r - k0); r <= Math.min(g.n - 1, m.r + k0); r++) {
            for (int c = Math.max(0, m.c - k0); c <= Math.min(g.n - 1, m.c + k0); c++) {
                if (!g.reachable(m, r, c, k0)) continue;
                if (!(r == m.r && c == m.c) && !g.at(r, c).isEmpty()) continue; // don't walk into anyone
                int i = g.idx(r, c);
                if (g.content[i].isPeril() && !g.opened[i]) continue;     // he knows his own traps
                int minD = Integer.MAX_VALUE, sum = 0;
                for (Player q : g.players) {
                    if (q == m || m.teammateOf(q)) continue;
                    int d = Math.abs(q.r - r) + Math.abs(q.c - c);
                    minD = Math.min(minD, d); sum += d;
                }
                if (minD == Integer.MAX_VALUE) minD = 0;
                double score = minD * 10 + sum * 0.1 + g.rng.nextDouble() * 0.5;
                if (score > bestScore) { bestScore = score; best = new int[]{r, c}; }
            }
        }
        if (best == null) { g.rest(m); g.endTurn(m); return; }
        g.move(m, best[0], best[1]);
        if (g.over) return;
        g.endTurn(m);
    }

    private void castSomething(Player m) {
        Player near = nearestOther(m, null);
        boolean wantPeril = near != null && near.dist(m) <= 2 * g.rules.dieMax();
        Content pick = null;
        for (Content c : m.hand) if (c.isPeril() == wantPeril) { pick = c; break; }
        if (pick == null) pick = m.hand.get(0);
        // the free slot nearest to the hunter (a trap in his path, or a gift that draws travellers together)
        Player aim = pick.isPeril() ? near : g.players.get(g.rng.nextInt(g.players.size()));
        if (aim == null || aim == m) aim = near;
        if (aim == null) return;
        int bestI = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < g.content.length; i++) {
            if (g.content[i] != Content.SLOT || g.opened[i]) continue;
            if (!g.at(i / g.n, i % g.n).isEmpty()) continue;
            int d = aim.dist(i / g.n, i % g.n);
            if (d < bestD) { bestD = d; bestI = i; }
        }
        if (bestI >= 0) g.cast(m, bestI, pick);
    }
}
