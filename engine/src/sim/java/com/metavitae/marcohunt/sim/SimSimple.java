package com.metavitae.marcohunt.sim;

import com.metavitae.marcohunt.engine.*;
import com.metavitae.marcohunt.engine.Types.*;
import java.util.*;
import java.util.stream.*;

/**
 * Simple Hunt (founder, Nov 8): you plus AI rivals, as in the app's Solo Hunt. Plays thousands of games with the real
 * engine and compares rule sets. "You" stands for two kinds of human: one who reasons (DETECTIVE) and one who just walks (WANDERER).
 * Run: java -cp build/main:build/sim com.metavitae.marcohunt.sim.SimSimple [seedBase] [games]
 */
public final class SimSimple {
    static long seedBase = 1; static int GAMES = 3000;

    static final class R { int found = Integer.MAX_VALUE; int finder = -1; boolean byAccuse; int steals, stopped, wrong, oracle, lies, hitsYou, helpersYou, takenFromYou; }

    static R play(Rules rules, long seed, Kind you) {
        long s = seed; Game g;
        do { g = new Game(rules, s++); } while (g.marco.id == 0);
        Ai ai = new Ai(g); ai.cunning = 0.8;
        Kind[] rivals = {Kind.FOLLOWER, Kind.FIGHTER, Kind.DETECTIVE};
        for (Player p : g.players) p.kind = p.id == 0 ? you : rivals[p.id % rivals.length];
        R x = new R();
        g.listeners.add(new Game.Listener() {
            @Override public void onPeril(Game gg, Player p, Content c, int st, boolean blocked) { if (p.id == 0 && !blocked) x.hitsYou++; }
            @Override public void onOpen(Game gg, Player p, Content c) { if (p.id == 0 && c.isPowerUp()) x.helpersYou++; }
            @Override public void onSteal(Game gg, Player t, Player v, Content c, boolean st) { if (v.id == 0 && c != null) x.takenFromYou++; }
        });
        ai.playToEnd();
        if (g.winBy == WinBy.LANDING || g.winBy == WinBy.ACCUSATION) { x.found = g.round; x.finder = g.finder.id; x.byAccuse = g.winBy == WinBy.ACCUSATION; }
        x.steals = g.steals; x.stopped = g.stealsStopped; x.wrong = g.wrongAccusations; x.oracle = g.oracleAnswers; x.lies = g.echoesSet;
        return x;
    }

    static List<R> run(Rules r, Kind you, long salt) {
        return IntStream.range(0, GAMES).parallel().mapToObj(i -> play(r, seedBase * 1_000_003L + salt * 10_007L + i * 7919L, you)).collect(Collectors.toList());
    }
    static String pct(double v) { return Math.round(v * 100) + "%"; }
    static double share(List<R> l, java.util.function.Predicate<R> f) { return l.stream().filter(f).count() / (double) l.size(); }
    static double avg(List<R> l, java.util.function.ToIntFunction<R> f) { return l.stream().mapToInt(f).average().orElse(0); }
    static int median(List<R> l) { int[] a = l.stream().mapToInt(x -> x.found).sorted().toArray(); return a[a.length / 2]; }

    static Rules config(String name, int n, int players) {
        Rules r;
        switch (name) {
            case "Simple, one lie": r = Rules.simpleHunt(n, players); break;
            case "Simple, no lie": r = Rules.simpleHunt(n, players); r.echoCharges = 0; break;
            case "Visible Marco, landing only": r = Rules.simpleHunt(n, players); r.accusations = false; break;
            case "Hidden Marco, one lie": r = Rules.simpleHunt(n, players); r.accusations = false; r.hiddenMarco = true; break;
            case "Hidden Marco, no lie": r = Rules.simpleHunt(n, players); r.accusations = false; r.hiddenMarco = true; r.echoCharges = 0; break;
            case "Hidden, Marco 1 square": r = Rules.simpleHunt(n, players); r.accusations = false; r.hiddenMarco = true; r.marcoStep = 1; r.walkUpTo = true; break;
            case "Hidden, Marco 2 squares": r = Rules.simpleHunt(n, players); r.accusations = false; r.hiddenMarco = true; r.marcoStep = 2; r.walkUpTo = true; break;
            case "Hidden, Marco 3 squares": r = Rules.simpleHunt(n, players); r.accusations = false; r.hiddenMarco = true; r.marcoStep = 3; r.walkUpTo = true; break;
            case "Visible, Marco 2 squares, one accusation": r = Rules.simpleHunt(n, players); r.marcoStep = 2; r.walkUpTo = true; break;
            case "Simple, lies without limit": r = Rules.simpleHunt(n, players); r.echoCharges = -1; break;
            default: r = new Rules(); r.n = n; r.players = players; r.teams = false; break; // today's Solo Hunt rules
        }
        r.dieSides = n <= 10 ? 6 : 10; r.maxRounds = 300; r.oracleTolerance = n >= 20 ? 2 : 1;
        return r;
    }

    static void table(String title, int n, int players, String[] names, int[] Ts, Kind you, long salt) {
        System.out.printf("%n### %s%n| Rules | Median rounds to find Marco | T | Marco escapes | You win | A rival wins | Solo minutes |%n|---|---|---|---|---|---|---|%n", title);
        for (String name : names) {
            List<R> l = run(config(name, n, players), you, salt);
            int med = median(l);
            for (int T : Ts) {
                double esc = share(l, x -> x.found > T), me = share(l, x -> x.found <= T && x.finder == 0), riv = share(l, x -> x.found <= T && x.finder != 0);
                double rounds = l.stream().mapToInt(x -> Math.min(x.found, T)).sorted().skip(l.size() / 2).findFirst().orElse(0);
                double minutes = rounds * (30 + 3 * (players - 1)) / 60.0; // your turn 30 s, each rival about 3 s, as played out in the app
                System.out.printf("| %s | %s | %d | %s | %s | %s | %.0f |%n", name, med > 300 ? "over 300" : "" + med, T, pct(esc), pct(me), pct(riv), minutes);
            }
        }
    }

    static void detail(String title, int n, int players, Kind you, long salt, int T) {
        List<R> l = run(config("Simple, one lie", n, players), you, salt).stream().filter(x -> true).collect(Collectors.toList());
        System.out.printf("%n### %s (whole games, before any turn limit)%n", title);
        System.out.printf("- Found by landing %s, by accusing %s of games where he is found%n", pct(share(l, x -> x.found < Integer.MAX_VALUE && !x.byAccuse) / Math.max(1e-9, share(l, x -> x.found < Integer.MAX_VALUE))), pct(share(l, x -> x.byAccuse) / Math.max(1e-9, share(l, x -> x.found < Integer.MAX_VALUE))));
        System.out.printf("- Per game: helpers you find %.1f, dangers that hit you %.1f, helpers taken from you %.1f, all takings %.1f, Shield stops %.1f, wrong accusations %.1f, Oracle answers %.1f, Marco's lies %.2f%n",
            avg(l, x -> x.helpersYou), avg(l, x -> x.hitsYou), avg(l, x -> x.takenFromYou), avg(l, x -> x.steals), avg(l, x -> x.stopped), avg(l, x -> x.wrong), avg(l, x -> x.oracle), avg(l, x -> x.lies));
    }

    public static void main(String[] a) {
        if (a.length > 0) seedBase = Long.parseLong(a[0]);
        if (a.length > 1) GAMES = Integer.parseInt(a[1]);
        long t0 = System.currentTimeMillis();
        String[] all = {"Simple, one lie", "Simple, no lie", "Visible Marco, landing only", "Hidden Marco, one lie", "Hidden Marco, no lie", "Today's Solo Hunt rules"};
        int[] Ts = {8, 10, 12, 15, 20, 25};
        System.out.printf("# Simple Hunt simulation, seed %d, %d games per setting (AI players only, no human has played these rules)%n", seedBase, GAMES);
        String[] speeds = {"Hidden, Marco 1 square", "Hidden, Marco 2 squares", "Hidden, Marco 3 squares"};
        table("10x10, you and 3 rivals, you reason, walk up to the roll", 10, 4, speeds, new int[]{8, 10, 12, 15, 20}, Kind.DETECTIVE, 1);
        table("10x10, you and 3 rivals, you just walk around, walk up to the roll", 10, 4, speeds, new int[]{8, 10, 12, 15, 20}, Kind.WANDERER, 2);
        table("10x10, you and 1 rival, you reason", 10, 2, speeds, new int[]{8, 10, 12, 15, 20}, Kind.DETECTIVE, 3);
        table("15x15 (d10), you and 3 rivals, you reason", 15, 4, speeds, new int[]{10, 15, 20, 25}, Kind.DETECTIVE, 4);
        table("10x10, visible Marco for comparison, you reason", 10, 4, new String[]{"Visible, Marco 2 squares, one accusation"}, new int[]{8, 12}, Kind.DETECTIVE, 5);
        System.out.printf("%n(done in %.0f s)%n", (System.currentTimeMillis() - t0) / 1000.0);
    }
}
