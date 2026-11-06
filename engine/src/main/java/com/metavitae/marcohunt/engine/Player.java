package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Types.*;
import java.util.ArrayList;
import java.util.List;

/** One traveller on the board. Marco is a Player with isMarco = true. */
public final class Player {
    public final int id;
    public final Cls cls;
    public final int team;          // -1 = alone
    public final boolean isMarco;
    public Kind kind;               // AI personality (ignored for humans)
    public boolean human = false;

    public int r, c;                // square
    public int prevR, prevC;        // square at the start of the last move (for sliding back)
    public int vigor;
    public final List<Content> items = new ArrayList<>();
    public int skillReadyAt = 0;    // round number when the class skill can be used again
    public int frozenTurns = 0;
    public int hiddenSignals = 0;   // Land of Darkness: signals still to hide
    public boolean shieldUp = false;
    public boolean accused = false;
    public double carry = 0;        // saved fraction of team opportunities
    public int bonusOpp = 0;        // spare opportunity from a fight won against an empty-handed loser
    public int stepsWalked = 0;
    // Marco only
    public final List<Content> hand = new ArrayList<>(); // contents Marco may still cast into slots
    public int echoesStarted = 0;
    public int lastEchoTurn = -999;

    public Player(int id, Cls cls, int team, boolean isMarco, int vigor) {
        this.id = id; this.cls = cls; this.team = team; this.isMarco = isMarco; this.vigor = vigor;
    }

    public boolean teammateOf(Player o) { return o != this && team >= 0 && team == o.team; }

    public int dist(int rr, int cc) { return Math.abs(r - rr) + Math.abs(c - cc); }
    public int dist(Player o) { return dist(o.r, o.c); }

    @Override public String toString() { return "P" + id + (isMarco ? "*" : "") + "(" + cls + "," + r + "," + c + ")"; }
}
