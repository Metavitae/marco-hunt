package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Spiral.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Rule tests for the spiral race, without any library. Exit code 1 if any test fails. */
public final class SpiralTests {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + what); } }

    static Spiral game(long seed) { return new Spiral(seed, new Kind[]{Kind.HUMAN, Kind.EASY, Kind.EASY, Kind.EASY}); }
    static boolean has(Walk w, Ev e) { return w.events.stream().anyMatch(x -> x.type == e); }
    /** A game with nothing on the road, so a test only sees what it sets up. */
    static Spiral clean(long seed) {
        Spiral g = game(seed);
        Arrays.fill(g.gift, false); Arrays.fill(g.tablet, false); Arrays.fill(g.horse, false);
        return g;
    }
    static Walk go(Spiral g, int roll, int dest) { g.roll = roll; return g.move(dest, g.reach()); }

    static void geometry() {
        int[] count = new int[5];
        for (int i = 0; i < Spiral.N; i++) count[Spiral.RING[i]]++;
        check(Arrays.equals(count, new int[]{36, 28, 20, 12, 4}), "rings are 36, 28, 20, 12, 4");
        for (int i = 0; i + 1 < Spiral.N; i++)
            check(Math.abs(Spiral.ROW[i] - Spiral.ROW[i + 1]) + Math.abs(Spiral.COL[i] - Spiral.COL[i + 1]) == 1, "road step " + i + " is to a neighbour");
        check(Spiral.RING[Spiral.KHAN] == 4, "the Khan is in the middle");
        check(Spiral.ROW[0] == 0 && Spiral.COL[0] == 0, "Venice is the corner");
        for (long s = 1; s <= 50; s++) {
            Spiral g = game(s);
            check(g.passes.size() == 4, "4 passes");
            for (int[] p : g.passes) {
                check(Spiral.RING[p[1]] == Spiral.RING[p[0]] + 1, "a pass joins side-by-side rings");
                check(Math.abs(Spiral.ROW[p[0]] - Spiral.ROW[p[1]]) + Math.abs(Spiral.COL[p[0]] - Spiral.COL[p[1]]) == 1, "a pass joins touching squares");
                check(p[1] != Spiral.KHAN && p[0] != Spiral.VENICE, "no pass at Venice or the Khan");
            }
            int gifts = 0; for (boolean b : g.gift) if (b) gifts++;
            check(gifts == 16, "16 gifts");
            check(!g.gift[0] && !g.gift[Spiral.KHAN], "no gift at the ends");
        }
    }

    static void gifts() {
        Spiral g = clean(1); Traveller p = g.ps[0];
        g.gift[3] = g.gift[5] = true;
        Walk w = go(g, 3, 3);
        check(has(w, Ev.GIFT) && p.gifts == 1 && !g.gift[3], "stopping on a gift takes it");
        w = go(g, 2, 5);
        check(!has(w, Ev.GIFT) && p.gifts == 1 && g.gift[5], "only one gift per land");
        // walking past a gift doesn't take it
        Spiral h = clean(1); h.gift[2] = true;
        go(h, 4, 4);
        check(h.ps[0].gifts == 0 && h.gift[2], "walking past a gift leaves it");
        // the gift is gone for everyone
        Spiral k = clean(1); k.gift[2] = true; go(k, 2, 2);
        check(!k.mayTakeGift(k.ps[1], 2), "a taken gift is gone for the rivals");
    }

    static void khan() {
        Spiral g = clean(2); Traveller p = g.ps[0];
        p.at = 97; p.gifts = 2; g.roll = 6;
        check(!g.reach().any(Spiral.KHAN), "2 gifts: the Khan's square is closed");
        p.gifts = 3;
        Spiral.Reach r = g.reach();
        check(r.any(Spiral.KHAN), "3 gifts: the Khan's square is open");
        Walk w = g.move(Spiral.KHAN, r);
        check(has(w, Ev.TREASURE) && p.chest && p.gifts == 0, "the Khan takes the gifts and gives the treasure");
    }

    static void home() {
        Spiral g = clean(3); Traveller p = g.ps[0];
        p.at = 3; g.roll = 6;
        check(!g.reach().any(Spiral.VENICE), "without a treasure Venice is not a stop");
        p.chest = true;
        Walk w = g.move(Spiral.VENICE, g.reach());
        check(has(w, Ev.WIN) && g.winner == 0 && g.over(), "home with the treasure wins");
    }

    static int[] passOf(Spiral g, int k) { return g.passes.get(k); }

    static void passes() {
        Spiral g = clean(4); Traveller p = g.ps[0]; int[] e = passOf(g, 0);
        p.at = e[0]; g.roll = 1; g.chance = () -> 0.9;
        Spiral.Reach r = g.reach();
        check(r.risky[e[1]] && !r.safe[e[1]], "the far side of a pass is marked risky");
        Walk w = g.move(e[1], r);
        check(has(w, Ev.PASS_OK) && p.at == e[1] && p.frozen == 0, "a pass that holds lets you through");

        g = clean(4); p = g.ps[0]; e = passOf(g, 0);
        p.at = e[0]; g.roll = 1; g.chance = () -> 0.1;
        int from = e[0];
        w = g.move(e[1], g.reach());
        check(has(w, Ev.PASS_FAIL) && p.at == Math.max(1, from - Spiral.THROWN_BACK) && p.frozen == 1, "a failed pass throws you back 6 and costs a turn");
        check(g.spendFrozen() && p.frozen == 0, "the frozen turn is used up");

        g = clean(4); p = g.ps[0]; e = passOf(g, 1);
        p.at = e[1]; p.chest = true; g.roll = 1; g.chance = () -> 0.1;
        from = e[1];
        g.move(e[0], g.reach());
        check(p.at == Math.min(Spiral.KHAN - 1, from + Spiral.THROWN_BACK), "going home, thrown back means towards the middle");

        g = clean(4); p = g.ps[0]; e = passOf(g, 0);
        p.at = e[0]; p.tablets = 1; g.roll = 1; g.chance = () -> 0.1;
        w = g.move(e[1], g.reach());
        check(has(w, Ev.PASS_SAVED) && p.at == e[1] && p.tablets == 0 && p.frozen == 0, "the Tablet of Gold saves a failed pass");
    }

    static Spiral robScene(double chance) {
        Spiral g = clean(5);
        g.ps[1].at = 50; g.ps[1].chest = true;
        g.ps[0].at = 48; g.chance = () -> chance;
        return g;
    }

    static void robbing() {
        Spiral g = robScene(0.1); g.ps[0].gifts = 3;
        Walk w = go(g, 2, 50);
        check(has(w, Ev.ROB) && g.ps[0].chest && !g.ps[1].chest, "3 gifts and a good roll: you take the treasure");
        check(g.ps[1].frozen == 1 && g.ps[1].audience && g.ps[1].robbedBy == 0, "the robbed lose a turn and may go back to the Khan");
        check(g.ps[1].frozen == 1 && g.mayEnter(g.ps[1]), "the robbed need no gifts to see the Khan again");

        g = robScene(0.9); g.ps[0].gifts = 3;
        w = go(g, 2, 50);
        check(has(w, Ev.ROB_MISS) && !g.ps[0].chest && g.ps[1].chest, "a bad roll: the robbery fails");

        g = robScene(0.1); g.ps[0].gifts = 2;
        w = go(g, 2, 50);
        check(w.events.isEmpty() && !g.ps[0].chest && g.ps[1].chest, "with under 3 gifts you can't rob");

        g = robScene(0.1); g.ps[0].gifts = 3; g.ps[1].tablets = 1;
        w = go(g, 2, 50);
        check(has(w, Ev.ROB_SAVED) && g.ps[1].chest && g.ps[1].tablets == 0, "the Tablet of Gold stops a robbery");

        // no robbing straight back (the endless loop the sim found)
        g = robScene(0.1); g.ps[0].gifts = 3;
        go(g, 2, 50);
        g.ps[1].at = 48; g.ps[1].frozen = 0; g.current = 1;
        w = go(g, 2, 50);
        check(w.events.isEmpty() && g.ps[0].chest, "you can't rob the one who just robbed you");

        // a third traveller can rob the thief
        g = robScene(0.1); g.ps[0].gifts = 3;
        go(g, 2, 50);
        g.ps[2].at = 48; g.ps[2].gifts = 3; g.current = 2;
        w = go(g, 2, 50);
        check(has(w, Ev.ROB) && g.ps[2].chest && !g.ps[0].chest, "a third traveller can rob the thief");
    }

    static Spiral playOut(Spiral g) {
        for (int turns = 0; turns < 4000 && !g.over(); turns++) {
            if (g.spendFrozen()) { g.endTurn(); continue; }
            g.rollDie();
            Spiral.Reach r = g.reach();
            g.move(g.choose(r), r);
            if (!g.over()) g.endTurn();
        }
        return g;
    }

    /** The port must keep the simulation's numbers (Nov 9: 45 rounds; careful vs 3 easy about 68%). */
    static void matchesSimulation() {
        int games = 1500, won = 0, finished = 0;
        List<Integer> rounds = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            Spiral g = playOut(new Spiral(7000L + i, new Kind[]{Kind.CAREFUL, Kind.CAREFUL, Kind.CAREFUL, Kind.CAREFUL}));
            if (g.over()) { finished++; rounds.add(g.round); }
        }
        Collections.sort(rounds);
        int median = rounds.isEmpty() ? -1 : rounds.get(rounds.size() / 2);
        check(finished == games, "every game ends (" + finished + "/" + games + ")");
        check(median >= 38 && median <= 52, "median length near 45 rounds (got " + median + ")");
        for (int i = 0; i < games; i++) {
            int seat = i % 4; Kind[] k = {Kind.EASY, Kind.EASY, Kind.EASY, Kind.EASY}; k[seat] = Kind.CAREFUL;
            Spiral g = playOut(new Spiral(9000L + i, k));
            if (g.winner == seat) won++;
        }
        double share = won / (double) games;
        check(share >= 0.60 && share <= 0.76, "careful vs 3 easy near 68% (got " + Math.round(share * 100) + "%)");
        System.out.println("median rounds " + median + ", careful vs easy " + Math.round(share * 100) + "%");
    }

    public static void main(String[] a) {
        geometry(); gifts(); khan(); home(); passes(); robbing(); matchesSimulation();
        System.out.println("spiral tests: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
