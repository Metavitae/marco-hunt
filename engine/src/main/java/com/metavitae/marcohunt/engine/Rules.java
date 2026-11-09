package com.metavitae.marcohunt.engine;

/**
 * Every tunable number of the game in one place. Defaults follow Notes v24 and the Task S assumptions;
 * the simulation changes them to find the best values.
 */
public final class Rules {
    public int n = 10;                 // board is n x n
    public int players = 4;            // 2 .. 8
    public int dieSides = 6;           // move die
    public int dieCount = 1;           // 2 = two dice added
    public int strengthSides = 6;      // die 2
    public double hot = 0.25, warm = 0.55;
    public boolean startCorner = false; // false = spread along the edges
    public boolean teams = true;       // equal teams when possible
    public boolean togetherBonus = true;
    public boolean specials = true;    // decorated squares, Spotlight, Oracle
    public boolean accusations = true;
    public boolean secondItems = false; // Knight also Leap, Magus also Falcon
    public int vigorMax = 3;
    public int restGain = 1;
    public int maxRounds = 400;        // hard stop for the simulation; the real limit T is applied on top
    public int turnLimit = 0;          // T: Marco wins after this many rounds (0 = none; simulation applies it afterwards)
    /** Echo limits. echoEvery = Marco may start a new echo only every k of his turns (1 = every turn). */
    public int echoEvery = 1;
    public int echoCharges = -1;       // -1 = no cap
    public int echoMaxLength = 4;      // how long an AI Marco sets an echo (rounds)
    public int oracleTolerance = 1;    // the Oracle says "about d squares": d is off by up to this
    public boolean divinationNeedsEmptySquares = true; // improvement: can't name a square a player stands on
    public double decoyShare = 0.5;    // empty decoys as a share of the special squares
    /** Oracle quorum: 0 = the whole team must stand on it (founder's rule); otherwise this share of the team, rounded up. */
    public double oracleQuorum = 0;
    /**
     * Simple Hunt (founder, Nov 8, after Faro's review): no class powers, hearts, fights, teams or casting.
     * Landing on a traveller takes one of their helpers (a Shield stops it). Helpers: Leap, Falcon, Shield.
     * Dangers: Freeze, Scatter, Darkness. Marco may lie once per game (echoCharges). Full Hunt keeps the old rules.
     */
    public boolean simple = false;
    /** Marco is not one of the visible travellers: he hides on the board and the signal leads to him. Only landing finds him. */
    public boolean hiddenMarco = false;
    /** Marco walks at most this many squares a turn instead of rolling the die (0 = he rolls like everyone). */
    public int marcoStep = 0;
    /** Walk up to the roll, not exactly the roll. */
    public boolean walkUpTo = false;
    /** Hidden Marco ambles: each turn he may stay or take a short walk at random, instead of fleeing with perfect knowledge. */
    public boolean marcoAmbles = false;
    /** The chance that an ambling Marco moves on a given turn. */
    public double marcoMoveChance = 0.5;
    /** Errands (idea from hidden-role games, Nov 8): Marco wins by visiting this many secret landmarks; 0 = no errands. */
    public int marcoErrands = 0;
    /** Each traveller also has this many secret landmarks to visit, which gives them a reason to walk and Marco cover. */
    public int travellerErrands = 0;
    /** The signal measures to where its target was this many rounds ago (0 = where he is now). */
    public int signalLag = 0;
    /** A wrong accusation costs this many missed turns. */
    public int wrongAccuseTurns = 0;
    /** True = landing on Marco finds him and landing on anyone else proves they aren't him. False = only an accusation can find him. */
    public boolean landingFinds = true;
    /** A traveller who finishes an errand earns a Falcon. */
    public boolean errandReward = true;

    /** The Simple Hunt's settings on an n x n board. */
    public static Rules simpleHunt(int n, int players) {
        Rules r = new Rules();
        r.n = n; r.players = players; r.simple = true; r.teams = false; r.togetherBonus = false;
        r.echoCharges = 1; r.echoMaxLength = 1;
        return r;
    }

    /** The game the app plays: hidden Marco on an n x n board. */
    public static Rules playable(int n, int players) {
        Rules r = simpleHunt(n, players);
        r.accusations = false; r.hiddenMarco = true; r.marcoStep = 1; r.walkUpTo = true; r.marcoAmbles = true; r.dieSides = 6;
        return r;
    }

    public int farthest() { return 2 * (n - 1); }
    public int hotMax() { return (int) Math.floor(hot * farthest()); }
    public int warmMax() { return (int) Math.floor(warm * farthest()); }
    public int dieMax() { return dieSides * dieCount; }

    /** Special squares: 25 percent, rounded to the nearest. */
    public int specialCount() { return (int) Math.round(0.25 * n * n); }
    public int perilCount() { return (specialCount() + 1) / 2; }
    public int powerUpCount() { return specialCount() / 2; }
    public int filledAtStart() { return (specialCount() + 1) / 2; }
    public int slotCount() { return specialCount() / 2; }
    public int decoyCount() { return (int) Math.floor(specialCount() * decoyShare); }

    public Rules copy() {
        Rules r = new Rules();
        r.n = n; r.players = players; r.dieSides = dieSides; r.dieCount = dieCount; r.strengthSides = strengthSides;
        r.hot = hot; r.warm = warm; r.startCorner = startCorner; r.teams = teams; r.togetherBonus = togetherBonus;
        r.specials = specials; r.accusations = accusations; r.secondItems = secondItems; r.vigorMax = vigorMax;
        r.restGain = restGain; r.maxRounds = maxRounds; r.turnLimit = turnLimit; r.echoEvery = echoEvery;
        r.echoCharges = echoCharges; r.echoMaxLength = echoMaxLength; r.oracleTolerance = oracleTolerance;
        r.divinationNeedsEmptySquares = divinationNeedsEmptySquares; r.decoyShare = decoyShare; r.oracleQuorum = oracleQuorum; r.simple = simple; r.hiddenMarco = hiddenMarco; r.marcoStep = marcoStep; r.walkUpTo = walkUpTo; r.marcoAmbles = marcoAmbles; r.marcoMoveChance = marcoMoveChance; r.marcoErrands = marcoErrands; r.travellerErrands = travellerErrands; r.signalLag = signalLag; r.wrongAccuseTurns = wrongAccuseTurns; r.landingFinds = landingFinds; r.errandReward = errandReward;
        return r;
    }
}
