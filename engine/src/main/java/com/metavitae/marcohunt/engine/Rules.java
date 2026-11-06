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
        r.divinationNeedsEmptySquares = divinationNeedsEmptySquares; r.decoyShare = decoyShare; r.oracleQuorum = oracleQuorum;
        return r;
    }
}
