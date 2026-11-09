package com.metavitae.marcohunt.sim;

import com.metavitae.marcohunt.engine.*;
import com.metavitae.marcohunt.engine.Types.*;
import java.util.*;
import java.util.stream.*;

/**
 * Can Marco stay one of the visible players? Errands, a signal that follows his trail, and a cost for accusing wrongly.
 * Solo table: you plus AI rivals (Marco is never you). All AI, no human has played this.
 * Run: java -cp build/main:build/sim com.metavitae.marcohunt.sim.SimErrands [seedBase] [games]
 */
public final class SimErrands {
    static long seedBase = 1; static int GAMES = 2000; static final int T = 15;
    static final class R { int end; WinBy by; int finder = -1; int wrong; int proven; }

    static R play(Rules rules, long seed, Kind you, boolean memory) {
        long s = seed; Game g;
        do { g = new Game(rules, s++); } while (g.marco.id == 0);
        Ai ai = new Ai(g); ai.cunning = 0.8; ai.perfectMemory = memory;
        Kind[] rivals = {Kind.FOLLOWER, Kind.FIGHTER, Kind.DETECTIVE};
        for (Player p : g.players) p.kind = p.id == 0 ? you : rivals[p.id % rivals.length];
        final int[] proven = {0};
        g.listeners.add(new Game.Listener() { @Override public void onNotMarco(Game gg, Player q) { proven[0]++; } });
        ai.playToEnd();
        R x = new R(); x.proven = proven[0]; x.end = g.round; x.by = g.winBy; x.finder = g.finder == null ? -1 : g.finder.id; x.wrong = g.wrongAccusations;
        return x;
    }

    static Rules noLanding(int players, int marcoE, int lag, int cost, boolean reward) {
        Rules r = cfg(players, marcoE, lag, cost); r.landingFinds = false; r.errandReward = reward; return r;
    }

    static Rules cfg(int players, int marcoE, int lag, int cost) {
        Rules r = Rules.simpleHunt(10, players);
        r.dieSides = 6; r.maxRounds = 300; r.walkUpTo = true;
        r.marcoErrands = marcoE; r.travellerErrands = marcoE > 0 ? 2 : 0; r.signalLag = lag; r.wrongAccuseTurns = cost;
        return r;
    }

    static void row(String name, Rules r, Kind you, boolean memory, long salt) {
        List<R> l = IntStream.range(0, GAMES).parallel().mapToObj(i -> play(r, seedBase * 1_000_003L + salt * 10_007L + i * 7919L, you, memory)).collect(Collectors.toList());
        double me = 0, riv = 0, mErr = 0, mTime = 0, acc = 0, found = 0, wrong = 0;
        int[] ends = new int[l.size()]; int k = 0;
        for (R x : l) {
            boolean inTime = x.end <= T;
            ends[k++] = Math.min(x.end, T);
            wrong += x.wrong;
            if (inTime && x.by == WinBy.ERRANDS) mErr++;
            else if (inTime && (x.by == WinBy.LANDING || x.by == WinBy.ACCUSATION)) {
                found++; if (x.by == WinBy.ACCUSATION) acc++;
                if (x.finder == 0) me++; else riv++;
            } else mTime++;
        }
        Arrays.sort(ends); int med = ends[ends.length / 2]; double n = l.size();
        System.out.printf("| %s | %d | %.0f | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %.1f |%n", name, med, med * (30 + 3 * (r.players - 1)) / 60.0,
            100 * mErr / n, 100 * mTime / n, 100 * me / n, 100 * riv / n, found == 0 ? 0 : 100 * acc / found, wrong / n);
    }

    public static void main(String[] a) {
        if (a.length > 0) seedBase = Long.parseLong(a[0]);
        if (a.length > 1) GAMES = Integer.parseInt(a[1]);
        System.out.printf("# Marco as a visible player, seed %d, %d games per row, 10x10, d6, walk up to the roll, one lie, limit %d rounds (AI only)%n", seedBase, GAMES, T);
        String head = "| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |\n|---|---|---|---|---|---|---|---|---|";
        System.out.println("\n### Sanity check: a signal with no information (always the same word), no Falcon, no Oracle help\n" + head);
        Rules dead = noLanding(4, 4, 0, 2, false); dead.hot = 2.0; dead.warm = 2.0; dead.echoCharges = 0;
        row("Only accusing, signal says nothing", dead, Kind.DETECTIVE, true, 1);
        int s = 10;
        double[][] bands = {{0.25, 0.55}, {0.17, 0.33}, {0.12, 0.22}, {0.06, 0.17}, {0.12, 0.12}};
        String[] names = {"today: hot 4, warm 9", "hot 3, warm 5", "hot 2, warm 3", "hot 1, warm 3", "close 2, else nothing"};
        for (int mode = 0; mode < 2; mode++) {
            System.out.println("\n### Short-range signal, errands 4, cost 2, " + (mode == 0 ? "landing still finds him" : "only accusing finds him") + ", you reason\n" + head);
            for (int i = 0; i < bands.length; i++) {
                Rules r = mode == 0 ? cfg(4, 4, 0, 2) : noLanding(4, 4, 0, 2, true);
                r.hot = bands[i][0]; r.warm = bands[i][1];
                row(names[i], r, Kind.DETECTIVE, true, s++);
            }
        }
        System.out.println("\n### Hot 2, warm 3, errand count, landing finds him\n" + head);
        for (int e : new int[]{3, 5, 6}) { Rules r = cfg(4, e, 0, 2); r.hot = 0.12; r.warm = 0.22; row("Errands " + e, r, Kind.DETECTIVE, true, s++); }
        System.out.println("\n### Hot 2, warm 3, errands 4, landing finds him: other players\n" + head);
        Rules w = cfg(4, 4, 0, 2); w.hot = 0.12; w.warm = 0.22; row("You just walk around", w, Kind.WANDERER, true, s++);
        Rules f = cfg(6, 4, 0, 2); f.hot = 0.12; f.warm = 0.22; row("You and 5 rivals, you reason", f, Kind.DETECTIVE, true, s++);
        Rules o = cfg(2, 4, 0, 2); o.hot = 0.12; o.warm = 0.22; row("You and 1 rival, you reason", o, Kind.DETECTIVE, true, s++);
    }
}
