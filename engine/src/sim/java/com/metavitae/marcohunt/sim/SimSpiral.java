package com.metavitae.marcohunt.sim;

import java.util.*;
import java.util.stream.*;

/**
 * Spiral there-and-back race (founder, Nov 9). Standalone: touches nothing in the engine or the app.
 * 10x10 board as one spiral road: square 0 = Venice (outer corner), 99 = the Khan (middle). Rings 36, 28, 20, 12, 4.
 * Risky passes join side-by-side rings. 3 gifts open the Khan's square; he gives a treasure; first home to Venice with one wins.
 * A traveller without a treasure who lands on one who has it robs them.
 * Run: java -cp build/spiral com.metavitae.marcohunt.sim.SimSpiral [seedBase] [games]
 */
public final class SimSpiral {
    static final int N = 100, KHAN = 99, VENICE = 0, LIMIT = 400;
    static final int[][] XY = new int[N][2];
    static final int[][] AT = new int[10][10];
    static final int[] RING = new int[N];
    static {
        int t = 0, b = 9, l = 0, r = 9, i = 0;
        while (t <= b && l <= r) {
            for (int c = l; c <= r; c++) put(i++, t, c);
            for (int w = t + 1; w <= b; w++) put(i++, w, r);
            if (t < b) for (int c = r - 1; c >= l; c--) put(i++, b, c);
            if (l < r) for (int w = b - 1; w > t; w--) put(i++, w, l);
            t++; b--; l++; r--;
        }
    }
    static void put(int i, int r, int c) { XY[i][0] = r; XY[i][1] = c; AT[r][c] = i; RING[i] = Math.min(Math.min(r, c), Math.min(9 - r, 9 - c)); }

    static final class Cfg implements Cloneable {
        String name; int players = 4, passes = 2, die = 6, giftsPerRing = 4, tablets = 4, horses = 4, penaltyTurns = 1, back = 0;
        double pHit = 0.5, robChance = 1.0; boolean onePerRing = true, robNeedsGifts = false;
        Cfg with(String n, java.util.function.Consumer<Cfg> f) { try { Cfg c = (Cfg) clone(); c.name = n; f.accept(c); return c; } catch (CloneNotSupportedException e) { throw new RuntimeException(e); } }
    }
    enum Kind { SMART, NEVER, ALWAYS, EASY, HUMAN, AMBUSH }

    static final class P { int id, at, gifts, ringsDone, frozen, bonus, tablets, walked, crossed, hit, robbedBy; boolean chest, audience; Kind kind; }
    static final class R { int rounds, winner = -1, robs, blocked, crossings, hits, leadChanges, youWalked, youCrossed; boolean leaderHalfWon, lastHalfWon, stuck, winnerWasRobbed, winnerStole; }

    static final class G {
        final Cfg c; final Random rnd; final P[] ps; final boolean[] gift = new boolean[N], tablet = new boolean[N], horse = new boolean[N];
        final int[][] adj = new int[N][]; final boolean[][] isPass = new boolean[N][N];
        final R res = new R(); final boolean[] stole, wasRobbed;
        G(Cfg c, long seed, Kind[] kinds) {
            this.c = c; rnd = new Random(seed); ps = new P[c.players]; stole = new boolean[c.players]; wasRobbed = new boolean[c.players];
            for (int i = 0; i < ps.length; i++) { ps[i] = new P(); ps[i].id = i; ps[i].kind = kinds[i]; ps[i].robbedBy = -1; }
            List<List<Integer>> a = new ArrayList<>();
            for (int i = 0; i < N; i++) a.add(new ArrayList<>());
            for (int i = 0; i + 1 < N; i++) { a.get(i).add(i + 1); a.get(i + 1).add(i); }
            for (int ring = 0; ring < 4; ring++) {
                List<int[]> cand = new ArrayList<>();
                for (int i = 1; i < N - 1; i++) if (RING[i] == ring) for (int[] d : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
                    int r = XY[i][0] + d[0], w = XY[i][1] + d[1];
                    if (r < 0 || w < 0 || r > 9 || w > 9) continue;
                    int j = AT[r][w];
                    if (RING[j] == ring + 1 && j - i > 3 && j != KHAN) cand.add(new int[]{i, j});
                }
                Collections.shuffle(cand, rnd);
                for (int k = 0; k < c.passes && k < cand.size(); k++) { int[] e = cand.get(k); a.get(e[0]).add(e[1]); a.get(e[1]).add(e[0]); isPass[e[0]][e[1]] = isPass[e[1]][e[0]] = true; }
            }
            for (int i = 0; i < N; i++) adj[i] = a.get(i).stream().mapToInt(x -> x).toArray();
            for (int ring = 0; ring < 4; ring++) {
                List<Integer> sq = new ArrayList<>();
                for (int i = 1; i < N - 1; i++) if (RING[i] == ring) sq.add(i);
                Collections.shuffle(sq, rnd);
                for (int k = 0; k < c.giftsPerRing; k++) gift[sq.get(k)] = true;
            }
            List<Integer> free = new ArrayList<>();
            for (int i = 1; i < N - 1; i++) if (!gift[i]) free.add(i);
            Collections.shuffle(free, rnd);
            int f = 0;
            for (int k = 0; k < c.tablets; k++) tablet[free.get(f++)] = true;
            for (int k = 0; k < c.horses; k++) horse[free.get(f++)] = true;
        }

        boolean canRob(P p) { return !p.chest && p.at != KHAN && (!c.robNeedsGifts || p.gifts >= 3 || p.audience); }
        boolean mayEnter(P p) { return p.gifts >= 3 || p.audience; }
        double passW(P p) {
            switch (p.kind) {
                case NEVER: return 1e6;
                case ALWAYS: return 1;
                default: return 1 + c.pHit * ((c.penaltyTurns + 1) * (c.die + 1) / 2.0 + c.back);
            }
        }
        /** Cheapest cost from src to every square; the Khan's square is only enterable with the right to enter. */
        double[] dist(int src, P p) {
            double[] d = new double[N]; Arrays.fill(d, 1e9); d[src] = 0;
            PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
            q.add(new double[]{0, src}); double pw = passW(p); boolean enter = mayEnter(p);
            while (!q.isEmpty()) {
                double[] u = q.poll(); int i = (int) u[1];
                if (u[0] > d[i]) continue;
                for (int j : adj[i]) {
                    if (j == KHAN && !enter) continue;
                    double w = isPass[i][j] ? pw : 1;
                    if (d[i] + w < d[j]) { d[j] = d[i] + w; q.add(new double[]{d[j], j}); }
                }
            }
            return d;
        }
        boolean eligibleGift(P p, int i) { return gift[i] && p.gifts < 3 && (!c.onePerRing || (p.ringsDone & (1 << RING[i])) == 0); }

        int target(P p, double[] fromHere) {
            if (p.chest) return VENICE;
            if (p.kind == Kind.AMBUSH && c.robNeedsGifts && p.gifts < 3 && !p.audience) { int b = -1; double d = 1e18; for (int i = 1; i < N - 1; i++) if (eligibleGift(p, i) && fromHere[i] < d) { d = fromHere[i]; b = i; } if (b >= 0) return b; }
            if (p.kind == Kind.AMBUSH || p.audience) {
                int best = p.kind == Kind.AMBUSH ? 90 : KHAN; double bd = p.kind == Kind.AMBUSH ? fromHere[90] + 8 : fromHere[KHAN];
                for (P o : ps) if (o != p && o.id != p.robbedBy && o.chest && o.at != VENICE && fromHere[o.at] < bd) { bd = fromHere[o.at]; best = o.at; }
                return best;
            }
            if (p.gifts >= 3) return KHAN;
            int best = -1; double bd = 1e18;
            for (int i = 1; i < N - 1; i++) if (eligibleGift(p, i) && fromHere[i] < bd) { bd = fromHere[i]; best = i; }
            return best < 0 ? KHAN : best;
        }

        void turn(P p) {
            if (p.frozen > 0) { p.frozen--; return; }
            int roll = 1 + rnd.nextInt(c.die) + p.bonus; p.bonus = 0;
            // every square reachable within the roll, by the fewest steps (road edges come first, so ties keep to the road)
            int[] prev = new int[N], steps = new int[N]; Arrays.fill(prev, -2); Arrays.fill(steps, 1 << 20);
            ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(p.at); prev[p.at] = -1; steps[p.at] = 0; boolean enter = mayEnter(p);
            if (p.kind == Kind.NEVER) { /* filtered below */ }
            while (!q.isEmpty()) {
                int i = q.poll(); if (steps[i] >= roll) continue;
                for (int j : adj[i]) {
                    if (prev[j] != -2 || (j == KHAN && !enter)) continue;
                    if (p.kind == Kind.NEVER && isPass[i][j]) continue;
                    prev[j] = i; steps[j] = steps[i] + 1; q.add(j);
                }
            }
            double[] here = dist(p.at, p), toT = dist(target(p, here), p);
            double noise = p.kind == Kind.EASY ? 4 : p.kind == Kind.HUMAN ? 2 : 0;
            int best = p.at; double bs = -1e18;
            for (int i = 0; i < N; i++) {
                if (prev[i] == -2) continue;
                if (i == VENICE && !p.chest) continue;
                double s = -toT[i] + (noise > 0 ? rnd.nextGaussian() * noise : 0);
                if (eligibleGift(p, i)) s += 3;
                if (tablet[i] && p.tablets == 0) s += 1.5;
                if (horse[i]) s += 1;
                if (canRob(p) && i != KHAN && i != VENICE) for (P o : ps) if (o != p && o.id != p.robbedBy && o.chest && o.at == i) s += p.kind == Kind.EASY ? 5 : 40;
                if (p.chest && p.tablets == 0 && (p.kind == Kind.SMART || p.kind == Kind.ALWAYS || p.kind == Kind.NEVER || p.kind == Kind.AMBUSH))
                    for (P o : ps) if (o != p && !o.chest && o.robbedBy != p.id && road(o.at, i) <= c.die) s -= 4;
                if (s > bs) { bs = s; best = i; }
            }
            // walk the path; a pass can stop the walk
            ArrayDeque<Integer> path = new ArrayDeque<>();
            for (int i = best; prev[i] >= 0; i = prev[i]) path.addFirst(i);
            int cur = p.at;
            for (int nx : path) {
                if (isPass[cur][nx]) {
                    p.crossed++; res.crossings++;
                    if (rnd.nextDouble() < c.pHit) {
                        if (p.tablets > 0) p.tablets--;
                        else {
                            p.hit++; res.hits++; p.frozen += c.penaltyTurns;
                            if (c.back > 0) cur = p.chest ? Math.min(KHAN - 1, cur + c.back) : Math.max(1, cur - c.back);
                            p.at = cur; return;
                        }
                    }
                }
                cur = nx; p.walked++;
            }
            p.at = cur;
            land(p);
        }
        int road(int a, int b) { return Math.abs(a - b); }
        void land(P p) {
            int i = p.at;
            if (i == KHAN && mayEnter(p) && !p.chest) { p.chest = true; p.gifts = 0; p.audience = false; return; }
            if (eligibleGift(p, i)) { gift[i] = false; p.gifts++; p.ringsDone |= 1 << RING[i]; }
            if (tablet[i] && p.tablets == 0) { tablet[i] = false; p.tablets = 1; }
            if (horse[i]) { horse[i] = false; p.bonus = 3; }
            if (canRob(p) && i != VENICE && i != KHAN) for (P o : ps) if (o != p && o.id != p.robbedBy && o.chest && o.at == i) {
                if (o.tablets > 0) { o.tablets--; res.blocked++; break; }
                if (rnd.nextDouble() >= c.robChance) break;
                o.chest = false; o.audience = true; o.robbedBy = p.id; o.frozen += 1; p.chest = true; p.audience = false; res.robs++; stole[p.id] = true; wasRobbed[o.id] = true; break;
            }
        }
        int remaining(P p) { return p.chest ? p.at : (KHAN - p.at) + KHAN; }
        int leader() { int b = 0; for (P p : ps) if (remaining(p) < remaining(ps[b])) b = p.id; return b; }
        int last() { int b = 0; for (P p : ps) if (remaining(p) > remaining(ps[b])) b = p.id; return b; }

        R play() {
            int prevLead = -1; List<Integer> leads = new ArrayList<>(), lasts = new ArrayList<>();
            for (int round = 1; round <= LIMIT; round++) {
                for (P p : ps) {
                    turn(p);
                    if (p.chest && p.at == VENICE) { finish(round, p, leads, lasts); return res; }
                }
                int l = leader(); if (prevLead >= 0 && l != prevLead) res.leadChanges++; prevLead = l; leads.add(l); lasts.add(last());
            }
            res.stuck = true; res.rounds = LIMIT; res.youWalked = ps[0].walked; res.youCrossed = ps[0].crossed; return res;
        }
        void finish(int round, P p, List<Integer> leads, List<Integer> lasts) {
            res.rounds = round; res.winner = p.id; res.youWalked = ps[0].walked; res.youCrossed = ps[0].crossed;
            res.winnerStole = stole[p.id]; res.winnerWasRobbed = wasRobbed[p.id];
            if (!leads.isEmpty()) { int h = leads.size() / 2; res.leaderHalfWon = leads.get(h) == p.id; res.lastHalfWon = lasts.get(h) == p.id; }
        }
    }

    static long seedBase = 1; static int GAMES = 3000;
    static List<R> run(Cfg c, Kind you, Kind others, long salt) {
        Kind[] k = new Kind[c.players]; Arrays.fill(k, others); k[0] = you;
        // rotate the seat of "you" so going first is not counted as skill
        return IntStream.range(0, GAMES).parallel().mapToObj(i -> {
            Kind[] kk = k.clone(); int seat = i % c.players; kk[0] = others; kk[seat] = you;
            R r = new G(c, seedBase * 1_000_003L + salt * 10_007L + i * 7919L, kk).play();
            if (seat != 0) { if (r.winner == seat) r.winner = 0; else if (r.winner == 0) r.winner = seat; }
            return r;
        }).collect(Collectors.toList());
    }
    static double share(List<R> l, java.util.function.Predicate<R> f) { return l.stream().filter(f).count() / (double) l.size(); }
    static double avg(List<R> l, java.util.function.ToDoubleFunction<R> f) { return l.stream().mapToDouble(f).average().orElse(0); }
    static int pctl(List<R> l, double q) { int[] a = l.stream().filter(x -> !x.stuck).mapToInt(x -> x.rounds).sorted().toArray(); return a.length == 0 ? -1 : a[(int) (q * (a.length - 1))]; }
    static String pct(double v) { return Math.round(v * 100) + "%"; }

    public static void main(String[] args) {
        if (args.length > 0) seedBase = Long.parseLong(args[0]);
        if (args.length > 1) GAMES = Integer.parseInt(args[1]);
        Cfg b = new Cfg(); b.name = "Base: 2 passes per ring, pass hits 1 in 2, lose 1 turn, 1 gift per ring";
        Cfg rb = b.with("Rob 1 in 2", c -> c.robChance = 0.5);
        List<Cfg> rows = List.of(rb,
            rb.with("Rob 1 in 2; 1 pass per ring, hits 2 in 3, back 6", c -> { c.passes = 1; c.pHit = 2 / 3.0; c.back = 6; }),
            rb.with("Rob 1 in 2; need 3 gifts to rob", c -> c.robNeedsGifts = true),
            rb.with("Rob 1 in 2; need 3 gifts to rob; 1 pass per ring, hits 1 in 2, back 6", c -> { c.robNeedsGifts = true; c.passes = 1; c.back = 6; }),
            rb.with("Rob 1 in 2; need 3 gifts to rob; 1 pass per ring, hits 2 in 3, back 6", c -> { c.robNeedsGifts = true; c.passes = 1; c.pHit = 2 / 3.0; c.back = 6; }),
            rb.with("Same as above, you + 2 rivals", c -> { c.robNeedsGifts = true; c.passes = 1; c.pHit = 2 / 3.0; c.back = 6; c.players = 3; }));
        System.out.println("# Spiral there-and-back sim, seed " + seedBase + ", " + GAMES + " games per cell\n");
        System.out.println("Minutes = rounds x 20 s (his late rounds on Nov 9 took about 18 s; full-roll walking may be slower). Fair share = 1 / players.\n");
        System.out.println("| Rules | Median rounds (10-90%) | Median min | Careful vs 3 easy | Half-careful vs 3 easy | Never cut vs careful | Always cut vs careful | Ambusher vs careful | Robs per game | Games with a rob | Halftime leader wins | Halftime last wins | Lead changes | Crossings per game | Squares you walk | Stuck |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        long salt = 1;
        for (Cfg c : rows) {
            List<R> all = run(c, Kind.SMART, Kind.SMART, salt++);
            double sm = share(run(c, Kind.SMART, Kind.EASY, salt++), r -> r.winner == 0);
            double hu = share(run(c, Kind.HUMAN, Kind.EASY, salt++), r -> r.winner == 0);
            double ne = share(run(c, Kind.NEVER, Kind.SMART, salt++), r -> r.winner == 0);
            double al = share(run(c, Kind.ALWAYS, Kind.SMART, salt++), r -> r.winner == 0);
            double am = share(run(c, Kind.AMBUSH, Kind.SMART, salt++), r -> r.winner == 0);
            int med = pctl(all, .5);
            System.out.printf("| %s | %d (%d-%d) | %d | %s | %s | %s | %s | %s | %.1f | %s | %s | %s | %.1f | %.1f | %.0f | %s |%n",
                c.name, med, pctl(all, .1), pctl(all, .9), Math.round(med * 20 / 60.0), pct(sm), pct(hu), pct(ne), pct(al), pct(am),
                avg(all, r -> r.robs), pct(share(all, r -> r.robs > 0)), pct(share(all, r -> r.leaderHalfWon)), pct(share(all, r -> r.lastHalfWon)),
                avg(all, r -> r.leadChanges), avg(all, r -> r.crossings), avg(all, r -> r.youWalked), pct(share(all, r -> r.stuck)));
        }
    }
}
