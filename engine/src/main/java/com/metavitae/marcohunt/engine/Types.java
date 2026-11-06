package com.metavitae.marcohunt.engine;

/** Plain value types shared by the engine, the AI, the simulation and the app. */
public final class Types {
    private Types() {}

    public enum Cls { KNIGHT, TRAVELER, MAGUS, MONGOL }

    /** What a square can hold. NONE = not decorated. DECOY = decorated, holds nothing. SLOT = decorated, empty until Marco casts. */
    public enum Content {
        NONE, DECOY, SLOT, ORACLE,
        LEAP, SHIELD, SPYGLASS, FALCON, COMPASS,          // power-ups
        SERPENT, DARKNESS, FREEZE, SCATTER;               // perils

        public boolean isPowerUp() { return this == LEAP || this == SHIELD || this == SPYGLASS || this == FALCON || this == COMPASS; }
        public boolean isPeril() { return this == SERPENT || this == DARKNESS || this == FREEZE || this == SCATTER; }
        public boolean isItem() { return isPowerUp() || isPeril(); }
    }

    public enum Band { HOT, WARM, COLD }

    /** How a peril meets a class. */
    public enum PerilFit { WELCOME, NORMAL, TERRIBLE }

    /** AI personalities (also used by the simulation). */
    public enum Kind { WANDERER, FOLLOWER, DETECTIVE, FIGHTER }

    public enum Dir { N, E, S, W }

    public enum WinBy { NONE, LANDING, ACCUSATION, TIME }

    /** Power-up that suits each class (Notes v24: Leap and Falcon: Mongol; Shield: Knight; Spyglass: Magus; Compass: Traveler). */
    public static boolean suits(Cls c, Content item, boolean secondItems) {
        switch (item) {
            // secondItems: balance lever from Task S (Knight also Leap, Magus also Falcon).
            case LEAP: return c == Cls.MONGOL || (secondItems && c == Cls.KNIGHT);
            case FALCON: return c == Cls.MONGOL || (secondItems && c == Cls.MAGUS);
            case SHIELD: return c == Cls.KNIGHT;
            case SPYGLASS: return c == Cls.MAGUS;
            case COMPASS: return c == Cls.TRAVELER;
            default: return false;
        }
    }

    /** Peril fit grid (Notes v24). */
    public static PerilFit perilFit(Cls c, Content peril) {
        switch (peril) {
            case SERPENT:  return c == Cls.KNIGHT ? PerilFit.WELCOME : (c == Cls.MONGOL ? PerilFit.NORMAL : PerilFit.TERRIBLE);
            case SCATTER:  return c == Cls.TRAVELER ? PerilFit.WELCOME : (c == Cls.MAGUS ? PerilFit.NORMAL : PerilFit.TERRIBLE);
            case DARKNESS: return c == Cls.MAGUS ? PerilFit.WELCOME : (c == Cls.MONGOL ? PerilFit.TERRIBLE : PerilFit.NORMAL);
            case FREEZE:   return c == Cls.MONGOL ? PerilFit.WELCOME : (c == Cls.MAGUS ? PerilFit.TERRIBLE : PerilFit.NORMAL);
            default: throw new IllegalArgumentException("not a peril: " + peril);
        }
    }

    /** Strength after fit: terrible S+2, normal S, welcome S-2 (never below 0). */
    public static int perilStrength(PerilFit f, int s) {
        switch (f) {
            case TERRIBLE: return s + 2;
            case WELCOME: return Math.max(0, s - 2);
            default: return s;
        }
    }

    /** Attack reach and strike bonus (accepted Nov 6 09:41). */
    public static int reach(Cls c) {
        switch (c) { case MAGUS: return 4; case MONGOL: return 3; case TRAVELER: return 2; default: return 1; }
    }
    public static int strikeBonus(Cls c) {
        switch (c) { case MAGUS: return 1; case MONGOL: return 2; case TRAVELER: return 3; default: return 4; }
    }
}
