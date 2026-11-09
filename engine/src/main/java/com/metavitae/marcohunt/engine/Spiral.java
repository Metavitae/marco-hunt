package com.metavitae.marcohunt.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * The spiral race (founder, Nov 9): Marco Polo's road as one spiral on a 10x10 board.
 * Square 0 is Venice (outer corner), square 99 the Khan (middle); rings of 36, 28, 20, 12 and 4 squares.
 * Three gifts, one per land, open the Khan's square; he gives a treasure; first back in Venice with one wins.
 * Rules are the best set from SimSpiral (CC/Log 'Spiral there-and-back race'); keep the two in step.
 */
public final class Spiral {
    public static final int N = 100, SIDE = 10, KHAN = 99, VENICE = 0, DIE = 6, GIFTS_NEEDED = 3, THROWN_BACK = 6, PASS_LOSES_TURNS = 1, HORSE_STEPS = 3;
    public static final int PASSES_PER_RING = 1, GIFTS_PER_RING = 4, TABLETS = 4, HORSES = 4;
    public static final double PASS_FAILS = 2 / 3.0, ROB_WORKS = 0.5;
    private static final double PASS_COST = 1 + PASS_FAILS * ((PASS_LOSES_TURNS + 1) * (DIE + 1) / 2.0 + THROWN_BACK);

    public static final int[] ROW = new int[N], COL = new int[N], RING = new int[N];
    public static final int[][] AT = new int[SIDE][SIDE];
    static {
        int t = 0, b = SIDE - 1, l = 0, r = SIDE - 1, i = 0;
        while (t <= b && l <= r) {
            for (int c = l; c <= r; c++) put(i++, t, c);
            for (int w = t + 1; w <= b; w++) put(i++, w, r);
            if (t < b) for (int c = r - 1; c >= l; c--) put(i++, b, c);
            if (l < r) for (int w = b - 1; w > t; w--) put(i++, w, l);
            t++; b--; l++; r--;
        }
    }
    private static void put(int i, int r, int c) {
        ROW[i] = r; COL[i] = c; AT[r][c] = i;
        RING[i] = Math.min(Math.min(r, c), Math.min(SIDE - 1 - r, SIDE - 1 - c));
    }

    public enum Kind { CAREFUL, EASY, HUMAN }
    public enum Ev { PASS_OK, PASS_SAVED, PASS_FAIL, GIFT, TABLET, HORSE, TREASURE, ROB, ROB_MISS, ROB_SAVED, WIN }

    public static final class Event {
        public final Ev type; public final int who, other, at, ring;
        Event(Ev type, int who, int other, int at, int ring) { this.type = type; this.who = who; this.other = other; this.at = at; this.ring = ring; }
    }

    public static final class Traveller {
        public int id, at, gifts, landsDone, frozen, bonus, tablets, robbedBy = -1;
        public boolean chest, audience;
        public Kind kind;
        public int walked, tried, failed;
    }

    /** Where a roll can take the traveller: safe = along the road; risky = only through a pass. */
    public static final class Reach {
        public final boolean[] safe = new boolean[N], risky = new boolean[N];
        final int[] prev = new int[N];
        public boolean any(int i) { return safe[i] || risky[i]; }
    }

    /** What a walk did: the squares walked in order (for the animation), and what happened. */
    public static final class Walk {
        public final List<Integer> squares = new ArrayList<>();
        public final List<Event> events = new ArrayList<>();
        public int thrownTo = -1;
    }

    public final Traveller[] ps;
    public final boolean[] gift = new boolean[N], tablet = new boolean[N], horse = new boolean[N];
    public final boolean[][] isPass = new boolean[N][N];
    /** The passes as pairs of squares (outer, inner). */
    public final List<int[]> passes = new ArrayList<>();
    public int current = 0, round = 1, roll = 0, winner = -1;
    /** Every chance in the game (tests replace it to force an outcome). */
    public DoubleSupplier chance;
    private final Random rnd;
    private final int[][] adj = new int[N][];

    public Spiral(long seed, Kind[] kinds) {
        rnd = new Random(seed);
        chance = rnd::nextDouble;
        ps = new Traveller[kinds.length];
        for (int i = 0; i < ps.length; i++) { ps[i] = new Traveller(); ps[i].id = i; ps[i].kind = kinds[i]; }
        List<List<Integer>> a = new ArrayList<>();
        for (int i = 0; i < N; i++) a.add(new ArrayList<>());
        for (int i = 0; i + 1 < N; i++) { a.get(i).add(i + 1); a.get(i + 1).add(i); }
        for (int ring = 0; ring < 4; ring++) {
            List<int[]> cand = new ArrayList<>();
            for (int i = 1; i < N - 1; i++) {
                if (RING[i] != ring) continue;
                for (int[] d : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
                    int r = ROW[i] + d[0], c = COL[i] + d[1];
                    if (r < 0 || c < 0 || r >= SIDE || c >= SIDE) continue;
                    int j = AT[r][c];
                    if (RING[j] == ring + 1 && j - i > 3 && j != KHAN) cand.add(new int[]{i, j});
                }
            }
            Collections.shuffle(cand, rnd);
            for (int k = 0; k < PASSES_PER_RING && k < cand.size(); k++) {
                int[] e = cand.get(k);
                a.get(e[0]).add(e[1]); a.get(e[1]).add(e[0]);
                isPass[e[0]][e[1]] = isPass[e[1]][e[0]] = true;
                passes.add(e);
            }
        }
        for (int i = 0; i < N; i++) { adj[i] = new int[a.get(i).size()]; for (int k = 0; k < adj[i].length; k++) adj[i][k] = a.get(i).get(k); }
        for (int ring = 0; ring < 4; ring++) {
            List<Integer> sq = new ArrayList<>();
            for (int i = 1; i < N - 1; i++) if (RING[i] == ring) sq.add(i);
            Collections.shuffle(sq, rnd);
            for (int k = 0; k < GIFTS_PER_RING; k++) gift[sq.get(k)] = true;
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 1; i < N - 1; i++) if (!gift[i]) free.add(i);
        Collections.shuffle(free, rnd);
        int f = 0;
        for (int k = 0; k < TABLETS; k++) tablet[free.get(f++)] = true;
        for (int k = 0; k < HORSES; k++) horse[free.get(f++)] = true;
    }

    public Traveller me() { return ps[current]; }
    public boolean over() { return winner >= 0; }
    public boolean mayEnter(Traveller p) { return p.gifts >= GIFTS_NEEDED || p.audience; }
    public boolean mayRob(Traveller p) { return !p.chest && p.at != KHAN && (p.gifts >= GIFTS_NEEDED || p.audience); }
    public boolean mayTakeGift(Traveller p, int i) { return gift[i] && p.gifts < GIFTS_NEEDED && (p.landsDone & (1 << RING[i])) == 0; }

    /** True if the current traveller is frozen: it uses the turn up. Call before rolling. */
    public boolean spendFrozen() { Traveller p = me(); if (p.frozen > 0) { p.frozen--; return true; } return false; }

    public int rollDie() { Traveller p = me(); roll = 1 + rnd.nextInt(DIE) + p.bonus; p.bonus = 0; return roll; }

    public Reach reach() {
        Traveller p = me(); Reach out = new Reach();
        boolean enter = mayEnter(p);
        Arrays.fill(out.prev, -2);
        int[] steps = new int[N];
        ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(p.at); out.prev[p.at] = -1;
        while (!q.isEmpty()) {
            int i = q.poll(); if (steps[i] >= roll) continue;
            for (int j : adj[i]) {
                if (out.prev[j] != -2 || (j == KHAN && !enter)) continue;
                out.prev[j] = i; steps[j] = steps[i] + 1; q.add(j);
            }
        }
        for (int d = Math.max(0, p.at - roll); d <= Math.min(KHAN, p.at + roll); d++) {
            if (d == KHAN && !enter) continue;
            if (d == VENICE && !p.chest) continue;
            out.safe[d] = true;
        }
        for (int d = 0; d < N; d++) out.risky[d] = out.prev[d] != -2 && !out.safe[d] && (d != VENICE || p.chest);
        return out;
    }

    /** Walks the current traveller to dest (from reach()). Does not end the turn. */
    public Walk move(int dest, Reach r) {
        Traveller p = me(); Walk w = new Walk();
        ArrayDeque<Integer> path = new ArrayDeque<>();
        if (r.safe[dest]) { int s = dest > p.at ? 1 : -1; for (int i = p.at; i != dest; i += s) path.addLast(i + s); }
        else for (int i = dest; r.prev[i] >= 0; i = r.prev[i]) path.addFirst(i);
        int cur = p.at;
        for (int nx : path) {
            if (isPass[cur][nx]) {
                p.tried++;
                int ring = Math.min(RING[cur], RING[nx]);
                if (chance.getAsDouble() < PASS_FAILS) {
                    if (p.tablets > 0) { p.tablets--; w.events.add(new Event(Ev.PASS_SAVED, p.id, -1, cur, ring)); }
                    else {
                        p.failed++; p.frozen += PASS_LOSES_TURNS;
                        cur = p.chest ? Math.min(KHAN - 1, cur + THROWN_BACK) : Math.max(1, cur - THROWN_BACK);
                        p.at = cur; w.thrownTo = cur;
                        w.events.add(new Event(Ev.PASS_FAIL, p.id, -1, cur, ring));
                        return w;
                    }
                } else w.events.add(new Event(Ev.PASS_OK, p.id, -1, cur, ring));
            }
            cur = nx; p.walked++; w.squares.add(nx);
        }
        p.at = cur;
        land(p, w);
        return w;
    }

    private void land(Traveller p, Walk w) {
        int i = p.at;
        if (i == VENICE && p.chest) { winner = p.id; w.events.add(new Event(Ev.WIN, p.id, -1, i, 0)); return; }
        if (i == KHAN && mayEnter(p) && !p.chest) {
            p.chest = true; p.gifts = 0; p.audience = false;
            w.events.add(new Event(Ev.TREASURE, p.id, -1, i, RING[i])); return;
        }
        if (mayTakeGift(p, i)) { gift[i] = false; p.gifts++; p.landsDone |= 1 << RING[i]; w.events.add(new Event(Ev.GIFT, p.id, -1, i, RING[i])); }
        if (tablet[i] && p.tablets == 0) { tablet[i] = false; p.tablets = 1; w.events.add(new Event(Ev.TABLET, p.id, -1, i, RING[i])); }
        if (horse[i]) { horse[i] = false; p.bonus = HORSE_STEPS; w.events.add(new Event(Ev.HORSE, p.id, -1, i, RING[i])); }
        if (mayRob(p) && i != VENICE) for (Traveller o : ps) {
            if (o == p || o.id == p.robbedBy || !o.chest || o.at != i) continue;
            if (o.tablets > 0) { o.tablets--; w.events.add(new Event(Ev.ROB_SAVED, p.id, o.id, i, RING[i])); break; }
            if (chance.getAsDouble() >= ROB_WORKS) { w.events.add(new Event(Ev.ROB_MISS, p.id, o.id, i, RING[i])); break; }
            o.chest = false; o.audience = true; o.robbedBy = p.id; o.frozen += 1;
            p.chest = true; p.audience = false;
            w.events.add(new Event(Ev.ROB, p.id, o.id, i, RING[i]));
            break;
        }
    }

    public void endTurn() { current = (current + 1) % ps.length; if (current == 0) round++; }

    private double[] dist(int src, Traveller p) {
        double[] d = new double[N]; Arrays.fill(d, 1e9); d[src] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
        q.add(new double[]{0, src}); boolean enter = mayEnter(p);
        while (!q.isEmpty()) {
            double[] u = q.poll(); int i = (int) u[1];
            if (u[0] > d[i]) continue;
            for (int j : adj[i]) {
                if (j == KHAN && !enter) continue;
                double w = isPass[i][j] ? PASS_COST : 1;
                if (d[i] + w < d[j]) { d[j] = d[i] + w; q.add(new double[]{d[j], j}); }
            }
        }
        return d;
    }

    private int target(Traveller p, double[] here) {
        if (p.chest) return VENICE;
        if (p.audience) {
            int best = KHAN; double bd = here[KHAN];
            for (Traveller o : ps) if (o != p && o.id != p.robbedBy && o.chest && o.at != VENICE && here[o.at] < bd) { bd = here[o.at]; best = o.at; }
            return best;
        }
        if (p.gifts >= GIFTS_NEEDED) return KHAN;
        int best = -1; double bd = 1e18;
        for (int i = 1; i < N - 1; i++) if (mayTakeGift(p, i) && here[i] < bd) { bd = here[i]; best = i; }
        return best < 0 ? KHAN : best;
    }

    /** The rival's choice of square for the current roll. */
    public int choose(Reach r) {
        Traveller p = me();
        double[] toT = dist(target(p, dist(p.at, p)), p);
        double noise = p.kind == Kind.EASY ? 4 : 0;
        int best = p.at; double bs = -1e18;
        for (int i = 0; i < N; i++) {
            if (!r.any(i)) continue;
            double s = -toT[i] + (noise > 0 ? rnd.nextGaussian() * noise : 0);
            if (mayTakeGift(p, i)) s += 3;
            if (tablet[i] && p.tablets == 0) s += 1.5;
            if (horse[i]) s += 1;
            if (mayRob(p) && i != KHAN && i != VENICE) for (Traveller o : ps) if (o != p && o.id != p.robbedBy && o.chest && o.at == i) s += p.kind == Kind.EASY ? 5 : 40;
            if (p.kind == Kind.CAREFUL && p.chest && p.tablets == 0) for (Traveller o : ps) if (o != p && !o.chest && o.robbedBy != p.id && Math.abs(o.at - i) <= DIE) s -= 4;
            if (s > bs) { bs = s; best = i; }
        }
        return best;
    }
}
