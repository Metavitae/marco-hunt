package com.metavitae.marcohunt.sim;

import com.metavitae.marcohunt.engine.*;
import com.metavitae.marcohunt.engine.Types.*;
import java.util.*;
import java.util.stream.*;

/**
 * Task S: plays thousands of AI games with the real rules engine and prints the tables for the Log.
 * Run: java -cp build/main:build/sim com.metavitae.marcohunt.sim.Sim [seedBase] [games]
 */
public final class Sim {

    /** One finished game, reduced to what the tables need. */
    static final class Rec {
        int found = Integer.MAX_VALUE; // round Marco was found (MAX = never within maxRounds)
        WinBy by = WinBy.TIME;
        Cls finderCls; Kind finderKind; int finderSeat = -1; boolean finderInTeam;
        Cls marcoCls;
        int players, teamSize;
        int oracleRound = -1, revealRound = -1;
        int[] steps; // cumulative walking squares per round
        int fights, wrongAcc, echoes, echoesFound;
        int[] clsCount = new int[4];
        boolean afterOracle;
        int stepsAt(int round) { if (steps.length == 0) return 0; return steps[Math.min(round, steps.length) - 1]; }
    }

    static long seedBase = 1;
    static int GAMES = 1000;

    public static void main(String[] a) {
        if (a.length > 0) seedBase = Long.parseLong(a[0]);
        if (a.length > 1) GAMES = Integer.parseInt(a[1]);
        String part = a.length > 2 ? a[2] : "all";
        long t0 = System.currentTimeMillis();
        if (part.equals("all") || part.equals("dice")) diceTable();
        if (part.equals("all") || part.equals("T")) turnLimitTable();
        if (part.equals("all") || part.equals("detail")) details();
        if (part.equals("lengths")) lengths();
        System.out.printf("%n(done in %.0f s, seed base %d, %d games per setting)%n", (System.currentTimeMillis() - t0) / 1000.0, seedBase, GAMES);
    }

    // ------------------------------------------------------------------ running

    static Rec play(Rules r, long seed, Kind table) {
        Game g = new Game(r, seed);
        Ai ai = new Ai(g);
        Rng kr = new Rng(seed * 31 + 7);
        Kind[] ks = Kind.values();
        for (Player p : g.players) p.kind = table != null ? table : ks[kr.nextInt(ks.length)];
        ai.playToEnd();
        Rec x = new Rec();
        x.players = r.players;
        int ts = 0;
        for (Player p : g.players) { if (p.team >= 0) ts++; x.clsCount[p.cls.ordinal()]++; }
        x.teamSize = 0;
        if (ts > 0) { int t = g.players.get(0).team; for (Player p : g.players) if (p.team == t) x.teamSize++; }
        x.marcoCls = g.marco.cls;
        if (g.winBy == WinBy.LANDING || g.winBy == WinBy.ACCUSATION) {
            x.found = g.round; x.by = g.winBy;
            x.finderCls = g.finder.cls; x.finderKind = g.finder.kind; x.finderSeat = g.finder.id; x.finderInTeam = g.finder.team >= 0;
        }
        x.oracleRound = g.firstOracleRound; x.revealRound = g.revealRound;
        x.afterOracle = g.firstOracleRound >= 0 && g.firstOracleRound <= x.found;
        x.steps = g.stepsByRound.stream().mapToInt(Integer::intValue).toArray();
        x.fights = g.fights; x.wrongAcc = g.wrongAccusations; x.echoes = g.echoesSet; x.echoesFound = g.echoesDetected;
        return x;
    }

    static List<Rec> run(Rules r, Kind table, long salt) {
        return IntStream.range(0, GAMES).parallel()
            .mapToObj(i -> play(r, seedBase * 1_000_003L + salt * 10_007L + i, table))
            .collect(Collectors.toList());
    }

    static Rules base(int n, int players, int sides, int count) {
        Rules r = new Rules();
        r.n = n; r.players = players; r.dieSides = sides; r.dieCount = count; r.maxRounds = 300;
        return r;
    }

    // ------------------------------------------------------------------ statistics

    static int quantileRound(List<Rec> recs, double q) {
        int[] f = recs.stream().mapToInt(x -> x.found).sorted().toArray();
        int i = (int) Math.ceil(q * f.length) - 1;
        return f[Math.max(0, Math.min(f.length - 1, i))];
    }

    static double marcoWin(List<Rec> recs, int T) { return recs.stream().filter(x -> x.found > T).count() / (double) recs.size(); }

    /** Median minutes of the game with limit T: turns x seconds + body walking at 1 m per second (walk mode). */
    static double medianMinutes(List<Rec> recs, int T, int secPerTurn, boolean walk) {
        double[] m = recs.stream().mapToDouble(x -> {
            int L = Math.min(x.found, T);
            double sec = (double) L * x.players * secPerTurn + (walk ? x.stepsAt(L) : 0);
            return sec / 60.0;
        }).sorted().toArray();
        return m[m.length / 2];
    }

    static String pct(double v) { return String.format("%d%%", Math.round(v * 100)); }
    static String fmtRound(int r) { return r == Integer.MAX_VALUE ? "never" : String.valueOf(r); }

    static final int[] SIZES = {10, 15, 20, 25, 30};
    static final int[][] DICE = {{6, 1}, {8, 1}, {10, 1}, {12, 1}, {20, 1}, {6, 2}, {8, 2}};
    static String dieName(int[] d) { return d[1] == 1 ? "d" + d[0] : "2d" + d[0]; }

    /** Turn limit T so that Marco wins about 30 percent (the middle of 1 in 4 .. 1 in 3). */
    static int tFor(List<Rec> recs, double marcoShare) { return quantileRound(recs, 1 - marcoShare); }

    // ------------------------------------------------------------------ 1. die per board

    static final Map<Integer, int[]> chosenDie = new HashMap<>();

    static void diceTable() {
        System.out.println("## 1. Move die per board (4 players, mixed table, spread start, hot/warm 25/55)");
        System.out.println("T30 = the turn limit (rounds) at which Marco wins 30 percent. Minutes = median game with that limit: table mode at 30 s and 60 s a turn, and walk mode at 30 s a turn plus walking (1 m squares, 1 m/s).");
        System.out.println("| Board | Die | Median rounds to find Marco (no limit) | Never found in 300 rounds | T30 | Table 30 s | Table 60 s | Walk 30 s + walking | Walked per player (m) |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");
        for (int n : SIZES) {
            double bestGap = 1e9; int[] best = null;
            for (int[] d : DICE) {
                List<Rec> recs = run(base(n, 4, d[0], d[1]), null, n * 100 + d[0] * 3 + d[1]);
                int med = quantileRound(recs, 0.5), T = tFor(recs, 0.30);
                double never = recs.stream().filter(x -> x.found == Integer.MAX_VALUE).count() / (double) recs.size();
                double m30 = medianMinutes(recs, T, 30, false), m60 = medianMinutes(recs, T, 60, false), w30 = medianMinutes(recs, T, 30, true);
                double walked = recs.stream().mapToDouble(x -> x.stepsAt(Math.min(x.found, T)) / 4.0).sorted().toArray()[recs.size() / 2];
                System.out.printf("| %dx%d | %s | %s | %s | %s | %.0f | %.0f | %.0f | %.0f |%n", n, n, dieName(d), fmtRound(med), pct(never), fmtRound(T), m30, m60, w30, walked);
                // choose the die whose walk-mode game at 30 s a turn is nearest to 22 minutes (the middle of 15 to 30)
                double gap = Math.abs(w30 - 22);
                if (gap < bestGap) { bestGap = gap; best = d; }
            }
            chosenDie.put(n, best);
        }
        System.out.print("Chosen die (walk mode at 30 s a turn nearest 22 minutes): ");
        for (int n : SIZES) System.out.print(n + "x" + n + " " + dieName(chosenDie.get(n)) + "  ");
        System.out.println();
    }

    static int[] die(int n) {
        int[] d = chosenDie.get(n);
        return d != null ? d : new int[]{n <= 10 ? 6 : n <= 15 ? 8 : n <= 20 ? 10 : 12, 1};
    }

    // ------------------------------------------------------------------ 2. turn limit T

    static void turnLimitTable() {
        System.out.println();
        System.out.println("## 2. Turn limit T (rounds) so Marco wins 1 in 4 / 3 in 10 / 1 in 3 (mixed table, chosen die)");
        System.out.println("| Board | Players | T for 25% | T for 30% | T for 33% | Median minutes (walk, 30 s) at T30 |");
        System.out.println("|---|---|---|---|---|---|");
        for (int n : SIZES) {
            int[] d = die(n);
            for (int np = 2; np <= 8; np++) {
                List<Rec> recs = run(base(n, np, d[0], d[1]), null, 50_000 + n * 10 + np);
                int T30 = tFor(recs, 0.30);
                System.out.printf("| %dx%d (%s) | %d | %s | %s | %s | %.0f |%n", n, n, dieName(d), np,
                    fmtRound(tFor(recs, 0.25)), fmtRound(T30), fmtRound(tFor(recs, 0.333)), medianMinutes(recs, T30, 30, true));
            }
        }
    }

    // ------------------------------------------------------------------ 3-7. details on the default board

    static void details() {
        int n = 10; int[] d = die(n);
        Rules r = base(n, 4, d[0], d[1]);
        List<Rec> mixed = run(r, null, 90_001);
        int T = tFor(mixed, 0.30);
        System.out.println();
        System.out.printf("## 3. How Marco is found (10x10, 4 players, %s, T = %d, mixed table)%n", dieName(d), T);
        long win = mixed.stream().filter(x -> x.found <= T).count();
        long land = mixed.stream().filter(x -> x.found <= T && x.by == WinBy.LANDING).count();
        long acc = mixed.stream().filter(x -> x.found <= T && x.by == WinBy.ACCUSATION).count();
        long orc = mixed.stream().filter(x -> x.oracleRound >= 0 && x.oracleRound <= Math.min(x.found, T)).count();
        long orcWin = mixed.stream().filter(x -> x.found <= T && x.afterOracle).count();
        long reveal = mixed.stream().filter(x -> x.revealRound >= 0 && x.revealRound <= Math.min(x.found, T)).count();
        System.out.printf("Hunters win %s: by landing %s, by accusation %s. Oracle answered in %s of games; hunters won after an Oracle answer in %s. Spotlight caught Marco in %s.%n",
            pct(win / (double) GAMES), pct(land / (double) GAMES), pct(acc / (double) GAMES), pct(orc / (double) GAMES), pct(orcWin / (double) GAMES), pct(reveal / (double) GAMES));
        System.out.printf("Per game (median): fights %d, wrong accusations %d, echoes set %d, echoes caught by a Spyglass %d.%n",
            med(mixed, x -> x.fights), med(mixed, x -> x.wrongAcc), med(mixed, x -> x.echoes), med(mixed, x -> x.echoesFound));

        System.out.println();
        System.out.println("## 4. Classes (10x10, 6 players, mixed table, 3000 games): who finds Marco, and how often Marco wins by his class");
        int saved = GAMES; GAMES = 3000;
        List<Rec> six = run(base(n, 6, d[0], d[1]), null, 90_002);
        int T6 = tFor(six, 0.30);
        GAMES = saved;
        System.out.println("| Class | Finds Marco (per player of that class) | Marco of that class wins |");
        System.out.println("|---|---|---|");
        for (Cls c : Cls.values()) {
            double players = six.stream().mapToInt(x -> x.clsCount[c.ordinal()] - (x.marcoCls == c ? 1 : 0)).sum();
            double finds = six.stream().filter(x -> x.found <= T6 && x.finderCls == c).count();
            List<Rec> asMarco = six.stream().filter(x -> x.marcoCls == c).collect(Collectors.toList());
            System.out.printf("| %s | %s | %s |%n", c, pct(finds / players), pct(marcoWin(asMarco, T6)));
        }

        System.out.println();
        System.out.println("## 5. Teams (10x10, mixed table, each at its own T30 from teams-on)");
        System.out.println("| Players | Teams on: Marco wins | Teams off: Marco wins | Teams on, no together bonus: Marco wins |");
        System.out.println("|---|---|---|---|");
        for (int np : new int[]{4, 6, 8}) {
            Rules on = base(n, np, d[0], d[1]);
            Rules off = on.copy(); off.teams = false;
            Rules nob = on.copy(); nob.togetherBonus = false;
            List<Rec> ron = run(on, null, 91_000 + np);
            int Tn = tFor(ron, 0.30);
            System.out.printf("| %d | %s | %s | %s |%n", np, pct(marcoWin(ron, Tn)), pct(marcoWin(run(off, null, 92_000 + np), Tn)), pct(marcoWin(run(nob, null, 93_000 + np), Tn)));
        }

        System.out.println();
        System.out.println("## 6. Hot/warm thresholds (10x10, 4 players, mixed table, T fixed at the 25/55 T30)");
        System.out.println("| Thresholds | Median rounds to find | Marco wins at T | Won by accusation |");
        System.out.println("|---|---|---|---|");
        for (double[] th : new double[][]{{0.15, 0.40}, {0.25, 0.55}, {0.35, 0.70}}) {
            Rules x = base(n, 4, d[0], d[1]); x.hot = th[0]; x.warm = th[1];
            List<Rec> rr = run(x, null, 94_000 + (int) (th[0] * 100));
            long ac = rr.stream().filter(y -> y.found <= T && y.by == WinBy.ACCUSATION).count();
            System.out.printf("| %.0f/%.0f | %s | %s | %s |%n", th[0] * 100, th[1] * 100, fmtRound(quantileRound(rr, 0.5)), pct(marcoWin(rr, T)), pct(ac / (double) GAMES));
        }

        System.out.println();
        System.out.println("## 7. Start: one corner or spread along the edges (10x10, 4 players): share of finds by seat (fair = 33% each for 3 hunters)");
        for (boolean corner : new boolean[]{true, false}) {
            Rules x = base(n, 4, d[0], d[1]); x.startCorner = corner;
            List<Rec> rr = run(x, null, corner ? 95_001 : 95_002);
            int[] seat = new int[4]; long tot = 0;
            for (Rec y : rr) if (y.found <= T && y.finderSeat >= 0) { seat[y.finderSeat]++; tot++; }
            System.out.printf("%s: Marco wins %s, median rounds %s, finds by seat 1..4: %s %s %s %s (seat counts include Marco's seat, who never finds)%n",
                corner ? "Corner" : "Spread", pct(marcoWin(rr, T)), fmtRound(quantileRound(rr, 0.5)),
                pct(seat[0] / (double) tot), pct(seat[1] / (double) tot), pct(seat[2] / (double) tot), pct(seat[3] / (double) tot));
        }

        System.out.println();
        System.out.println("## 8. Echo limits (10x10, 4 players, mixed table, same T)");
        System.out.println("| Echo rule | Marco wins | Won by accusation | Wrong accusations per game (mean) |");
        System.out.println("|---|---|---|---|");
        Object[][] echoRules = {{"no cap (every turn)", 1, -1}, {"every 3rd turn", 3, -1}, {"3 per game", 1, 3}, {"no echo at all", 1, 0}};
        for (Object[] e : echoRules) {
            Rules x = base(n, 4, d[0], d[1]); x.echoEvery = (Integer) e[1]; x.echoCharges = (Integer) e[2];
            List<Rec> rr = run(x, null, 96_000 + (Integer) e[1] * 10 + (Integer) e[2] + 5);
            long ac = rr.stream().filter(y -> y.found <= T && y.by == WinBy.ACCUSATION).count();
            double wrong = rr.stream().mapToInt(y -> y.wrongAcc).average().orElse(0);
            System.out.printf("| %s | %s | %s | %.2f |%n", e[0], pct(marcoWin(rr, T)), pct(ac / (double) GAMES), wrong);
        }

        System.out.println();
        System.out.println("## 9. Kinds of player (10x10, 4 players, whole table of one kind, same T)");
        System.out.println("| Table | Median rounds to find | Marco wins |");
        System.out.println("|---|---|---|");
        for (Kind k : Kind.values()) {
            List<Rec> rr = run(base(n, 4, d[0], d[1]), k, 97_000 + k.ordinal());
            System.out.printf("| all %s | %s | %s |%n", k, fmtRound(quantileRound(rr, 0.5)), pct(marcoWin(rr, T)));
        }

        System.out.println();
        System.out.println("## 10. Two players (10x10): the lone hunter knows the other player is Marco");
        List<Rec> two = run(base(n, 2, d[0], d[1]), null, 98_000);
        System.out.printf("Median rounds %s, won by accusation %s, Marco wins at T=%d: %s%n", fmtRound(quantileRound(two, 0.5)),
            pct(two.stream().filter(y -> y.by == WinBy.ACCUSATION && y.found <= T).count() / (double) GAMES), T, pct(marcoWin(two, T)));
        Rules twoNoAcc = base(n, 2, d[0], d[1]); twoNoAcc.accusations = false;
        List<Rec> two2 = run(twoNoAcc, null, 98_001);
        System.out.printf("Same, accusations off for 2 players: median rounds %s, Marco wins at T=%d: %s%n", fmtRound(quantileRound(two2, 0.5)), T, pct(marcoWin(two2, T)));
    }

    /** Game lengths (Chat, Nov 6): Quick ~20, Standard ~45, Expedition 60-90 minutes, walk mode at 30 s a turn. */
    static void lengths() {
        System.out.println("## Game lengths: the board and die whose median game (walk mode, 30 s a turn, Marco wins 3 in 10) is nearest each target");
        System.out.println("| Length (target) | Players | Board | Die | T (rounds) | Median minutes | 1 in 4 games longer than |");
        System.out.println("|---|---|---|---|---|---|---|");
        int[] players = {4, 6};
        String[] names = {"Quick (20)", "Standard (45)", "Expedition (75)"};
        double[] target = {20, 45, 75};
        for (int np : players) {
            // measure every board x die once
            java.util.List<double[]> rows = new java.util.ArrayList<>();
            for (int n : SIZES) for (int[] d : DICE) {
                List<Rec> recs = run(base(n, np, d[0], d[1]), null, 70_000 + n * 100 + d[0] * 3 + d[1] + np * 7);
                int T = tFor(recs, 0.30);
                if (T == Integer.MAX_VALUE) continue;
                double med = medianMinutes(recs, T, 30, true);
                double[] m = recs.stream().mapToDouble(x -> { int L = Math.min(x.found, T); return ((double) L * x.players * 30 + x.stepsAt(L)) / 60.0; }).sorted().toArray();
                rows.add(new double[]{n, d[0], d[1], T, med, m[(int) (m.length * 0.75)]});
            }
            for (int k = 0; k < 3; k++) {
                double[] best = null;
                for (double[] r : rows) if (best == null || Math.abs(r[4] - target[k]) < Math.abs(best[4] - target[k])) best = r;
                System.out.printf("| %s | %d | %.0fx%.0f | %s | %.0f | %.0f | %.0f |%n", names[k], np, best[0], best[0],
                    best[2] == 1 ? "d" + (int) best[1] : "2d" + (int) best[1], best[3], best[4], best[5]);
            }
        }
    }

    interface IntF { int f(Rec r); }
    static int med(List<Rec> recs, IntF f) { int[] v = recs.stream().mapToInt(f::f).sorted().toArray(); return v[v.length / 2]; }
}
