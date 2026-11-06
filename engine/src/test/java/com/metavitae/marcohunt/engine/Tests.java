package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Types.*;
import java.util.function.Predicate;

/** Rule tests without any library: run with `java ... Tests`. Exit code 1 if any test fails. */
public final class Tests {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL: " + what); }
    }

    static Rules plain(int n, int players) {
        Rules r = new Rules(); r.n = n; r.players = players; r.specials = false; r.teams = false; return r;
    }

    /** First seed whose game satisfies the predicate (so tests can pick who is Marco, the teams, ...). */
    static Game find(Rules r, Cls[] cls, Predicate<Game> want) {
        for (long s = 1; s < 100000; s++) { Game g = new Game(r, s, cls); if (want.test(g)) return g; }
        throw new IllegalStateException("no seed");
    }

    static void place(Player p, int r, int c) { p.r = p.prevR = r; p.c = p.prevC = c; }

    public static void main(String[] a) {
        countsMatchNotes();
        bandsMatchNotes();
        knightReadsWarmAsCold();
        exactStepsReachability();
        perilFitGrid();
        landingFindsMarco();
        teammateDoesNotFindMarco();
        landingOnOtherStartsFightAndProvesNotMarco();
        accusationRules();
        echoRedirectsAndSpyglassClears();
        spotlightReveals();
        teamOpportunities();
        oracleNeedsFullVigorOrItem();
        falconAndCompass();
        strikeBackNeedsReach();
        perilsHurt();
        determinism();
        thousandGames();
        handCheckTinyBoard();
        System.out.println("tests passed: " + pass + ", failed: " + fail);
        if (fail > 0) System.exit(1);
    }

    static void countsMatchNotes() {
        int[][] want = { // n, special, power-ups, perils, filled, slots, decoys
            {10, 25, 12, 13, 13, 12, 12}, {15, 56, 28, 28, 28, 28, 28}, {20, 100, 50, 50, 50, 50, 50},
            {25, 156, 78, 78, 78, 78, 78}, {30, 225, 112, 113, 113, 112, 112}};
        for (int[] w : want) {
            Rules r = new Rules(); r.n = w[0];
            check(r.specialCount() == w[1] && r.powerUpCount() == w[2] && r.perilCount() == w[3]
                && r.filledAtStart() == w[4] && r.slotCount() == w[5] && r.decoyCount() == w[6], "counts for " + w[0]);
            Game g = new Game(r, 7);
            int filled = 0, slots = 0, decoys = 0, oracle = 0, compass = 0;
            for (Content ct : g.content) {
                if (ct.isItem()) filled++;
                if (ct == Content.SLOT) slots++;
                if (ct == Content.DECOY) decoys++;
                if (ct == Content.ORACLE) oracle++;
                if (ct == Content.COMPASS) compass++;
            }
            for (Content ct : g.marco.hand) if (ct == Content.COMPASS) compass++;
            check(filled == w[4] && slots == w[5] && decoys == w[6] && oracle == 1, "placed counts for " + w[0]);
            check(filled + g.marco.hand.size() == w[1], "pool size for " + w[0]);
            check(compass == 1, "exactly one compass on " + w[0]);
            check(g.content[g.spotlight] == Content.NONE, "spotlight looks plain");
        }
    }

    static void bandsMatchNotes() {
        int[][] want = {{10, 4, 9}, {15, 7, 15}, {20, 9, 20}, {30, 14, 31}};
        for (int[] w : want) {
            Rules r = new Rules(); r.n = w[0];
            check(r.hotMax() == w[1] && r.warmMax() == w[2], "bands for " + w[0] + ": " + r.hotMax() + "/" + r.warmMax());
        }
    }

    static void knightReadsWarmAsCold() {
        Game g = new Game(plain(10, 2), 1);
        check(g.reading(Cls.KNIGHT, 6) == Band.COLD && g.reading(Cls.MAGUS, 6) == Band.WARM, "knight warm->cold");
        check(g.reading(Cls.KNIGHT, 3) == Band.HOT, "knight still reads hot");
    }

    static void exactStepsReachability() {
        Game g = new Game(plain(10, 2), 1);
        Player p = g.players.get(0); place(p, 0, 0);
        check(g.reachable(p, 0, 3, 3) && g.reachable(p, 0, 1, 3) && !g.reachable(p, 0, 2, 3), "parity rule");
        check(!g.reachable(p, 0, 4, 3) && !g.reachable(p, -1, 0, 3), "too far / off board");
        check(g.reachable(p, 0, 0, 2), "back to the start with an even roll");
    }

    static void perilFitGrid() {
        check(Types.perilFit(Cls.KNIGHT, Content.SERPENT) == PerilFit.WELCOME, "knight serpent welcome");
        check(Types.perilFit(Cls.TRAVELER, Content.SERPENT) == PerilFit.TERRIBLE, "traveler serpent terrible");
        check(Types.perilFit(Cls.MONGOL, Content.DARKNESS) == PerilFit.TERRIBLE, "mongol darkness terrible");
        check(Types.perilFit(Cls.MAGUS, Content.FREEZE) == PerilFit.TERRIBLE, "magus freeze terrible");
        check(Types.perilFit(Cls.TRAVELER, Content.SCATTER) == PerilFit.WELCOME, "traveler scatter welcome");
        check(Types.perilStrength(PerilFit.TERRIBLE, 3) == 5 && Types.perilStrength(PerilFit.WELCOME, 1) == 0
            && Types.perilStrength(PerilFit.NORMAL, 4) == 4, "strengths S+2, S, S-2>=0");
        check(Types.suits(Cls.MONGOL, Content.LEAP, false) && Types.suits(Cls.KNIGHT, Content.SHIELD, false)
            && Types.suits(Cls.MAGUS, Content.SPYGLASS, false) && Types.suits(Cls.TRAVELER, Content.COMPASS, false)
            && !Types.suits(Cls.KNIGHT, Content.LEAP, false) && Types.suits(Cls.KNIGHT, Content.LEAP, true), "power-up fit");
    }

    static void landingFindsMarco() {
        Game g = find(plain(10, 3), null, x -> x.marco.id == 1);
        Player h = g.players.get(0); place(h, 0, 0); place(g.marco, 0, 3); place(g.players.get(2), 9, 9);
        g.turnIndex = 0; g.beginTurn(); g.forceRoll(3);
        g.move(h, 0, 3);
        check(g.over && g.winBy == WinBy.LANDING && g.finder == h, "landing on Marco finds him");
    }

    static void teammateDoesNotFindMarco() {
        Rules r = plain(10, 4); r.teams = true;
        Game g = find(r, null, x -> x.marco.id == 1 && x.players.get(0).team == x.marco.team && x.marco.team >= 0);
        Player h = g.players.get(0); place(h, 0, 0); place(g.marco, 0, 2);
        for (int i = 2; i < 4; i++) place(g.players.get(i), 9, 9 - i);
        g.turnIndex = 0; g.beginTurn(); g.forceRoll(2);
        g.move(h, 0, 2);
        check(!g.over && g.fights == 0, "teammate on Marco's square neither finds nor fights");
    }

    static void landingOnOtherStartsFightAndProvesNotMarco() {
        Game g = find(plain(10, 3), new Cls[]{Cls.KNIGHT, Cls.KNIGHT, Cls.KNIGHT}, x -> x.marco.id == 2);
        Player h = g.players.get(0), q = g.players.get(1);
        place(h, 0, 0); place(q, 0, 2); place(g.marco, 9, 9);
        final boolean[] seen = {false};
        g.listeners.add(new Game.Listener() { public void onNotMarco(Game gg, Player x) { if (x == q) seen[0] = true; } });
        g.turnIndex = 0; g.beginTurn(); g.forceRoll(2);
        g.move(h, 0, 2);
        check(!g.over && g.fights == 1 && seen[0], "landing on a non-Marco starts a fight and proves he is not Marco");
    }

    static void accusationRules() {
        Game g = find(plain(10, 3), null, x -> x.marco.id == 2);
        Player h = g.players.get(0);
        g.turnIndex = 0; g.beginTurn();
        check(!g.accuse(h, g.players.get(1)) && g.wrongAccusations == 1, "wrong accusation");
        boolean threw = false;
        try { g.accuse(h, g.marco); } catch (IllegalStateException e) { threw = true; }
        check(threw && !g.over, "only one accusation per game");
        Game g2 = find(plain(10, 3), null, x -> x.marco.id == 2);
        g2.turnIndex = 0; g2.beginTurn();
        check(g2.accuse(g2.players.get(0), g2.marco) && g2.over && g2.winBy == WinBy.ACCUSATION, "right accusation wins");
    }

    static void echoRedirectsAndSpyglassClears() {
        Game g = find(plain(10, 3), null, x -> x.marco.id == 0);
        Player m = g.marco, decoy = g.players.get(2), h = g.players.get(1);
        place(m, 0, 0); place(h, 5, 5); place(decoy, 9, 9);
        g.turnIndex = 0; g.beginTurn();
        g.setEcho(m, decoy, 0, 3);
        check(g.signalTarget() == decoy, "echo points the signal at the decoy");
        boolean threw = false;
        try { g.setEcho(m, m, 0, 2); } catch (RuntimeException e) { threw = true; }
        check(threw, "an echo cannot point at Marco himself");
        g.rest(m); g.endTurn(m);
        g.beginTurn(); h.items.add(Content.SPYGLASS);
        g.useSpyglass(h);
        check(g.signalTarget() == m && g.echoesDetected == 1, "spyglass destroys the echo");
        Game g2 = find(plain(10, 3), null, x -> x.marco.id == 0);
        g2.turnIndex = 0; g2.beginTurn();
        g2.setEcho(g2.marco, g2.players.get(1), 2, 2);
        check(g2.signalTarget() == g2.marco, "a delayed echo waits");
    }

    static void spotlightReveals() {
        Rules r = new Rules(); r.n = 10; r.players = 3; r.teams = false;
        Game g = find(r, null, x -> x.marco.id == 0);
        int sr = g.spotlight / 10, sc = g.spotlight % 10;
        Player m = g.marco;
        place(m, sr, sc == 0 ? 1 : sc - 1);
        g.turnIndex = 0; g.beginTurn(); g.forceRoll(1);
        g.move(m, sr, sc);
        check(g.marcoRevealed, "Marco on the Spotlight is revealed");
        if (!g.over) { g.endTurn(m); g.beginTurn();
            boolean threw = false;
            try { g.accuse(g.current(), m); } catch (IllegalStateException e) { threw = true; }
            check(threw, "no accusations once revealed (the hunt goes on by landing)"); }
    }

    static void teamOpportunities() {
        Rules r = plain(10, 4); r.teams = true;
        Game g = find(r, null, x -> x.players.get(0).team >= 0 && x.players.get(0).team == x.players.get(1).team && !x.players.get(0).isMarco);
        Player a = g.players.get(0), b = g.players.get(1);
        place(a, 4, 4); place(b, 4, 4);
        for (int i = 2; i < 4; i++) place(g.players.get(i), 9, 9 - i);
        g.turnIndex = 0; g.beginTurn();
        check(g.opportunitiesLeft() == 1, "a pair together: 1.5 opportunities = 1 now");
        g.rest(a); g.endTurn(a);
        check(Math.abs(a.carry - 1.0) < 1e-9, "unused 1.5 saves 1 (capped)");
        while (g.turnIndex != 0) { Player p = g.current(); g.beginTurn(); g.rest(p); g.endTurn(p); }
        g.beginTurn();
        check(g.opportunitiesLeft() == 2, "next turn: 1.5 + saved = 2 opportunities");
        g.rest(a); g.endTurn(a);
        place(b, 0, 0);
        while (g.turnIndex != 0) { Player p = g.current(); g.beginTurn(); g.rest(p); g.endTurn(p); }
        g.beginTurn();
        check(g.opportunitiesLeft() == 1 && a.carry == 0, "apart: normal turn, saved part lost");
    }

    static void oracleNeedsFullVigorOrItem() {
        Rules r = new Rules(); r.n = 10; r.players = 3; r.teams = false;
        Game g = find(r, null, x -> x.marco.id == 2);
        Player h = g.players.get(0);
        int or = g.oracle / 10, oc = g.oracle % 10;
        g.opened[g.oracle] = true; g.oracleKnown = true;
        place(h, or, oc); h.vigor = 2;
        g.turnIndex = 0; g.beginTurn(); g.rest(h); // rest restores to 3 and then asks
        check(g.oracleAnswers == 1, "full vigor after resting on the Oracle square earns an answer");
        Game g2 = find(r, null, x -> x.marco.id == 2);
        Player h2 = g2.players.get(0);
        g2.opened[g2.oracle] = true; g2.oracleKnown = true;
        place(h2, g2.oracle / 10, g2.oracle % 10); h2.vigor = 1;
        g2.turnIndex = 0; g2.beginTurn(); g2.rest(h2);
        check(g2.oracleAnswers == 0, "not at full vigor: no answer");
        h2.items.add(g2.oracleTaskItem);
        g2.endTurn(h2);
        while (g2.turnIndex != 0) { Player p = g2.current(); if (g2.beginTurn()) { g2.rest(p); g2.endTurn(p); } }
        g2.beginTurn(); h2.vigor = 0; g2.rest(h2);
        check(g2.oracleAnswers == 1 && !h2.items.contains(g2.oracleTaskItem), "the task item stands in for vigor (and is used up)");
    }

    static void falconAndCompass() {
        Game g = find(plain(10, 3), null, x -> x.marco.id == 1);
        Player h = g.players.get(0); place(h, 5, 5); place(g.marco, 1, 4); place(g.players.get(2), 9, 0);
        g.turnIndex = 0; g.beginTurn();
        h.items.add(Content.FALCON);
        check(g.useFalcon(h) == Dir.N, "falcon says north");
        g.rest(h); g.endTurn(h);
        // compass on a later turn of the same player
        while (g.turnIndex != 0) { Player p = g.current(); if (g.beginTurn()) { g.rest(p); g.endTurn(p); } }
        g.beginTurn(); h.items.add(Content.COMPASS);
        int v = g.useCompass(h);
        check(v == Math.abs(h.r - g.marco.r) || v == Math.abs(h.c - g.marco.c), "compass gives rows or columns apart");
    }

    static void strikeBackNeedsReach() {
        // a Magus attacks a Knight from 4 squares: the Knight (reach 1) can never win that fight
        int knightWins = 0;
        for (long s = 1; s <= 300; s++) {
            Game g = new Game(plain(10, 2), s, new Cls[]{Cls.MAGUS, Cls.KNIGHT});
            Player a = g.players.get(0), b = g.players.get(1);
            place(a, 0, 0); place(b, 0, 4);
            g.turnIndex = 0; g.beginTurn();
            int w = g.attack(a, b);
            if (w == b.id) knightWins++;
        }
        check(knightWins == 0, "no strike back from outside the target's reach");
    }

    static void perilsHurt() {
        Rules r = plain(10, 2);
        int moved = 0, frozen = 0;
        for (long s = 1; s <= 200; s++) {
            Game g = find(r, new Cls[]{Cls.TRAVELER, Cls.KNIGHT}, x -> x.marco.id == 1);
            Player h = g.players.get(0); place(h, 5, 5); place(g.marco, 0, 9);
            g.turnIndex = 0; g.beginTurn();
            g.content[g.idx(5, 7)] = Content.SERPENT;
            g.forceRoll(2); g.move(h, 5, 7);
            if (h.c < 7) moved++;
            check(h.vigor == 2, "a peril costs one vigor");
            Game g2 = find(r, new Cls[]{Cls.MAGUS, Cls.KNIGHT}, x -> x.marco.id == 1);
            Player m2 = g2.players.get(0); place(m2, 5, 5); place(g2.marco, 0, 9);
            g2.turnIndex = 0; g2.beginTurn();
            g2.content[g2.idx(5, 6)] = Content.FREEZE;
            g2.forceRoll(1); g2.move(m2, 5, 6);
            if (m2.frozenTurns == 1) frozen++;
            break;
        }
        check(moved == 1, "serpent slides the traveller back");
        check(frozen == 1, "freeze takes the next turn");
        // shield cancels
        Game g = find(r, new Cls[]{Cls.TRAVELER, Cls.KNIGHT}, x -> x.marco.id == 1);
        Player h = g.players.get(0); place(h, 5, 5); place(g.marco, 0, 9);
        h.items.add(Content.SHIELD);
        g.turnIndex = 0; g.beginTurn();
        g.content[g.idx(5, 7)] = Content.SERPENT;
        g.forceRoll(2); g.move(h, 5, 7);
        check(h.c == 7 && h.vigor == 3 && !h.items.contains(Content.SHIELD), "shield cancels the peril");
    }

    static void determinism() {
        Rules r = new Rules(); r.n = 15; r.players = 5;
        String a = playSummary(r, 42), b = playSummary(r, 42), c = playSummary(r, 43);
        check(a.equals(b), "same seed, same game");
        check(!a.equals(c), "other seed, other game (very likely)");
    }

    static String playSummary(Rules r, long seed) {
        Game g = new Game(r, seed);
        Ai ai = new Ai(g);
        Kind[] ks = Kind.values();
        for (Player p : g.players) p.kind = ks[p.id % ks.length];
        ai.playToEnd();
        return g.winBy + "/" + g.round + "/" + g.fights + "/" + g.totalSteps;
    }

    static void thousandGames() {
        int broken = 0, crashed = 0;
        Kind[] ks = Kind.values();
        for (int i = 0; i < 1000; i++) {
            Rules r = new Rules();
            int[] sizes = {10, 15, 20, 25, 30};
            r.n = sizes[i % 5]; r.players = 2 + (i / 5) % 7; r.startCorner = i % 2 == 0;
            r.dieSides = new int[]{6, 8, 10, 12, 20}[(i / 3) % 5];
            r.maxRounds = 150;
            Game g = new Game(r, 1000 + i);
            Ai ai = new Ai(g);
            for (Player p : g.players) p.kind = ks[(i + p.id) % ks.length];
            try {
                while (!g.over) {
                    Player p = g.current();
                    if (g.beginTurn()) ai.playTurn(p);
                    String err = g.checkInvariants();
                    if (err != null) { broken++; if (broken < 4) System.out.println("rule broken in game " + i + ": " + err); break; }
                }
            } catch (RuntimeException e) {
                crashed++;
                if (crashed < 4) { System.out.println("crash in game " + i + ": " + e); e.printStackTrace(System.out); }
            }
        }
        check(broken == 0, "1000 AI games keep every rule (" + broken + " broke)");
        check(crashed == 0, "1000 AI games without a crash (" + crashed + " crashed)");
    }

    /**
     * Hand check: 3 x 3, 2 players, no special squares, no accusations. The hunter at (0,0) moves first,
     * Marco at (2,2) is 4 away. With a d6 the hunter lands on him at once only with a 4 or a 6: 2 in 6.
     */
    static void handCheckTinyBoard() {
        Rules r = plain(3, 2); r.accusations = false;
        int found = 0, games = 30000;
        long seed = 5000;
        for (int i = 0; i < games; i++) {
            Game g = new Game(r, seed++);
            if (g.marco.id != 1) { i--; continue; }
            Player h = g.players.get(0); place(h, 0, 0); place(g.marco, 2, 2);
            g.turnIndex = 0; g.beginTurn();
            int k = g.moveAllowance();
            if (g.reachable(h, 2, 2, k)) { g.move(h, 2, 2); if (g.over) found++; }
        }
        double share = found / (double) games;
        System.out.printf("hand check: first-move find rate %.4f (by hand 1/3 = 0.3333)%n", share);
        check(Math.abs(share - 1.0 / 3) < 0.015, "hand check matches 1/3");
    }
}
