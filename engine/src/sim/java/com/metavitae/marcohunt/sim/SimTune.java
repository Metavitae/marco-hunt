package com.metavitae.marcohunt.sim;

import com.metavitae.marcohunt.engine.*;
import com.metavitae.marcohunt.engine.Types.*;
import java.util.*;
import java.util.stream.*;

/** Sets the difficulty of the hidden-Marco Solo Hunt. Seat 0 stands for you: careful, half careful, or just walking. AI only. */
public final class SimTune {
    static int GAMES = 2000; static long seedBase = 1;
    static int[] play(Rules r, long seed, double youSkill, double rivalSkill, Kind you) {
        long s = seed; Game g;
        do { g = new Game(r, s++); } while (g.marco.id == 0);
        Ai ai = new Ai(g); ai.cunning = youSkill; ai.rivalSkill = rivalSkill;
        for (Player p : g.players) p.kind = p.id == 0 ? you : Kind.FOLLOWER;
        ai.playToEnd();
        boolean found = g.winBy == WinBy.LANDING;
        return new int[]{g.round, found ? g.finder.id : -1};
    }
    static void row(int players, int T, double rival, double chance, double youSkill, Kind you, String who, long salt) {
        Rules r = Rules.playable(10, players); r.marcoMoveChance = chance; r.maxRounds = 300;
        List<int[]> l = IntStream.range(0, GAMES).parallel().mapToObj(i -> play(r, seedBase * 1_000_003L + salt * 10_007L + i * 7919L, youSkill, rival, you)).collect(Collectors.toList());
        double me = 0, riv = 0, esc = 0; int[] ends = new int[l.size()]; int k = 0;
        for (int[] x : l) { ends[k++] = Math.min(x[0], T); if (x[1] < 0 || x[0] > T) esc++; else if (x[1] == 0) me++; else riv++; }
        Arrays.sort(ends); int med = ends[ends.length / 2]; double N = l.size();
        System.out.printf("| %d rivals | limit %d | rival skill %.1f | moves %.0f%% | %s | %d | %.0f | %.0f%% | %.0f%% | %.0f%% |%n", players - 2, T, rival, chance * 100, who, med, med * (30 + 3 * (players - 1)) / 60.0, 100 * me / N, 100 * riv / N, 100 * esc / N);
    }
    public static void main(String[] a) {
        if (a.length > 0) seedBase = Long.parseLong(a[0]);
        System.out.println("| Rivals | Limit | Rival skill | Marco moves | You | Median rounds | Solo minutes | You win | Rival wins | Marco escapes |\n|---|---|---|---|---|---|---|---|---|---|");
        long s = 1;
        for (double rival : new double[]{0.0}) for (int T : new int[]{25}) {
            row(4, T, rival, 0.25, 1.0, Kind.DETECTIVE, "careful", s++);
            row(4, T, rival, 0.25, 0.6, Kind.DETECTIVE, "half careful", s++);
            row(4, T, rival, 0.25, 1.0, Kind.WANDERER, "just walks", s++);
        }
    }
}
