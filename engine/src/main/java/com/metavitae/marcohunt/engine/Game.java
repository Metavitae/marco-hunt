package com.metavitae.marcohunt.engine;

import com.metavitae.marcohunt.engine.Types.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole rules engine of Marco Hunt: pure logic, no screen, deterministic for a given seed.
 * Humans (the app) and the AI (Ai.java) drive it through the same calls:
 *   beginTurn() -> [actions: useItem / useSkill / attack / accuse / cast / setEcho] -> move(..) or rest() -> endTurn()
 * Private information is delivered through Listener callbacks; everything else is public state.
 */
public final class Game {

    /** Private and public news for players and the app. Default methods so a listener implements only what it needs. */
    public interface Listener {
        default void onSignal(Game g, Player p, Band band) {}              // end-of-turn temperature (private)
        default void onSignalHidden(Game g, Player p) {}                   // Land of Darkness ate it
        default void onFalcon(Game g, Player p, Dir dir) {}                // private
        default void onCompass(Game g, Player p, boolean rows, int value) {} // private
        default void onDivination(Game g, Player p, int ar, int ac, int br, int bc, int answer) {} // private: -1 A nearer, 1 B nearer, 0 same
        default void onSpyglass(Game g, Player p, boolean echoFound) {}     // private
        default void onOracle(Game g, Player p, int mark, int about) {}     // private to the team: marks[mark], about d squares
        default void onNotMarco(Game g, Player q) {}                        // public: q was landed on and fought or traded, so q is not Marco
        default void onWrongAccusation(Game g, Player accuser, Player q) {} // private to the accuser
        default void onReveal(Game g, Player marco) {}                      // public: Spotlight
        default void onFight(Game g, Player a, Player b, int winner) {}     // public: winner id or -1
        default void onOver(Game g) {}
        default void onOpen(Game g, Player p, Content what) {}             // public: a decorated square was opened (what it held)
        default void onPeril(Game g, Player p, Content peril, int strength, boolean blocked) {}
        /** Simple Hunt: thief landed on victim and took this helper, or null if a Shield stopped it or the sac was empty. */
        default void onSteal(Game g, Player thief, Player victim, Content taken, boolean shieldStopped) {} // public
    }

    public static final class Echo {
        public final int start, end, decoyId;
        Echo(int start, int end, int decoyId) { this.start = start; this.end = end; this.decoyId = decoyId; }
    }

    public final Rules rules;
    public final Rng rng;
    public final int n;
    public final Content[] content;
    public final boolean[] opened;
    /** Squares Marco filled by casting (for the end-of-game reveal: luck vs Marco). */
    public final boolean[] castByMarco;
    public int[] trapsFromMarco, trapsFromLuck;
    public int spotlight = -1;
    public int steals = 0, stealsStopped = 0;
    /** errands[player] = landmark numbers still to visit (empty when done or no errands). */
    public List<List<Integer>> errands = new ArrayList<>();
    /** Everyone's position at the end of each round, for a signal that follows the trail. */
    public final List<int[][]> history = new ArrayList<>();
    public int oracle = -1;
    public Content oracleTaskItem = Content.SHIELD;
    /** The Oracle's big landmarks (6, placed at random each game) and the small marks near them: {row, col, type, parent landmark}. */
    public int[][] landmarks;
    public int[][] marks;
    /** Squares held by landmarks and marks: never decorated. */
    public boolean[] reserved;
    /** Marco's square at the end of each round (the Oracle speaks about where he used to be). */
    public final List<int[]> marcoTrail = new ArrayList<>();
    public static final int MARK_TYPES = 4; // fountain, falcon perch, palm, shrine
    public final List<Player> players = new ArrayList<>();
    public Player marco;
    public final List<Listener> listeners = new ArrayList<>();

    public int round = 1;
    public int turnIndex = 0;
    public boolean over = false;
    public WinBy winBy = WinBy.NONE;
    public Player finder = null;
    public boolean marcoRevealed = false;
    public boolean oracleKnown = false;
    public final List<Echo> echoes = new ArrayList<>();
    final boolean[] teamAskedOracle = new boolean[16];

    // per-turn state
    int moveRoll = 0, moveExtra = 0;
    double budget = 0;
    int used = 0;
    boolean together = false;
    boolean moved = false;

    // statistics
    public int fights = 0, wrongAccusations = 0, echoesSet = 0, echoesDetected = 0, perilsHit = 0, perilsBlocked = 0;
    public int oracleFoundRound = -1;
    public int oracleAnswers = 0, firstOracleRound = -1, revealRound = -1, casts = 0, trades = 0;
    public final List<Integer> stepsByRound = new ArrayList<>(); // cumulative body-walking squares at the end of each round
    public int totalSteps = 0;

    public Game(Rules rules, long seed) { this(rules, seed, null); }

    /** classes may be null (random) or give one class per player. */
    public Game(Rules rules, long seed, Cls[] classes) {
        this.rules = rules;
        this.rng = new Rng(seed);
        this.n = rules.n;
        if (n < 2) throw new IllegalArgumentException("board too small");
        if (rules.players < 2) throw new IllegalArgumentException("need 2 players");
        content = new Content[n * n];
        opened = new boolean[n * n];
        castByMarco = new boolean[n * n];
        for (int i = 0; i < content.length; i++) content[i] = Content.NONE;
        setupPlayers(classes);
        placeLandmarks();
        setupErrands();
        trapsFromMarco = new int[players.size()]; trapsFromLuck = new int[players.size()];
        if (rules.specials) setupSquares();
    }

    // ------------------------------------------------------------------ setup

    /**
     * Randomizing rule (founder, Nov 6): everything is placed at random each game, except that the riddle pieces follow a rule:
     * 6 landmarks spread apart, and near each one a few small marks of different types, each nearer to its own landmark than to
     * any other. So "near X there is a Y" always points at exactly one square.
     */
    private void setupErrands() {
        for (Player p : players) {
            List<Integer> all = new ArrayList<>();
            int want = p.isMarco ? rules.marcoErrands : rules.travellerErrands;
            if (want > 0) {
                for (int i = 0; i < landmarks.length; i++) all.add(i);
                rng.shuffle(all);
                while (all.size() > want) all.remove(all.size() - 1);
            }
            errands.add(all);
        }
    }

    /** Where this player stood lag rounds ago (or as far back as the game goes). */
    public int[] pastPos(Player q, int lag) {
        if (lag <= 0 || history.isEmpty()) return new int[]{q.r, q.c};
        int[][] snap = history.get(Math.max(0, history.size() - lag));
        return snap[q.id];
    }

    /** Landing on one of your errand places ticks it off. Marco wins when his are done; a traveller earns a Falcon. */
    private void checkErrand(Player p) {
        List<Integer> mine = errands.isEmpty() ? null : errands.get(p.id);
        if (mine == null || mine.isEmpty()) return;
        for (int k = 0; k < mine.size(); k++) {
            int[] L = landmarks[mine.get(k)];
            if (L[0] != p.r || L[1] != p.c) continue;
            mine.remove(k);
            if (p.isMarco) { if (mine.isEmpty()) finish(WinBy.ERRANDS, p); }
            else if (rules.errandReward) p.items.add(Content.FALCON);
            return;
        }
    }

    private void placeLandmarks() {
        reserved = new boolean[n * n];
        boolean[] start = new boolean[n * n];
        for (Player p : players) start[idx(p.r, p.c)] = true;
        int count = 6;
        landmarks = new int[count][];
        int lo = n >= 6 ? 1 : 0, hi = n >= 6 ? n - 2 : n - 1;
        for (int spacing = Math.max(3, n / 3); spacing >= 1; spacing--) {
            int placed = 0;
            for (int tries = 0; tries < 400 && placed < count; tries++) {
                int r = lo + rng.nextInt(hi - lo + 1), c = lo + rng.nextInt(hi - lo + 1);
                if (start[idx(r, c)]) continue;
                boolean ok = true;
                for (int k = 0; k < placed; k++) if (Math.abs(landmarks[k][0] - r) + Math.abs(landmarks[k][1] - c) < spacing) ok = false;
                if (ok) landmarks[placed++] = new int[]{r, c};
            }
            if (placed == count) break;
        }
        for (int[] L : landmarks) reserved[idx(L[0], L[1])] = true;
        int perLandmark = n <= 10 ? 2 : 3;
        List<int[]> ms = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            List<Integer> types = new ArrayList<>();
            for (int t = 0; t < MARK_TYPES; t++) types.add(t);
            rng.shuffle(types);
            List<int[]> near = new ArrayList<>();
            for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) {
                int r = landmarks[k][0] + dr, c = landmarks[k][1] + dc, d = Math.abs(dr) + Math.abs(dc);
                if (d < 1 || d > 2 || !onBoard(r, c) || reserved[idx(r, c)] || start[idx(r, c)]) continue;
                boolean own = true;
                for (int j = 0; j < count; j++) if (j != k && Math.abs(landmarks[j][0] - r) + Math.abs(landmarks[j][1] - c) <= d) own = false;
                if (own) near.add(new int[]{r, c});
            }
            rng.shuffle(near);
            for (int t = 0; t < perLandmark && t < near.size(); t++) {
                int[] sq = near.get(t);
                reserved[idx(sq[0], sq[1])] = true;
                ms.add(new int[]{sq[0], sq[1], types.get(t), k});
            }
        }
        marks = ms.toArray(new int[0][]);
    }

    /** Team size used for this headcount: 2..4, at least two teams, or 0 (everyone alone). Random among the options. */
    public static int teamSize(int players, boolean teamsOn, Rng rng) {
        if (!teamsOn) return 0;
        List<Integer> opts = new ArrayList<>();
        for (int s = 2; s <= 4; s++) if (players % s == 0 && players / s >= 2) opts.add(s);
        if (opts.isEmpty()) return 0;
        return opts.get(rng.nextInt(opts.size()));
    }

    private void setupPlayers(Cls[] classes) {
        int np = rules.players;
        int size = teamSize(np, rules.teams, rng);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < np; i++) order.add(i);
        rng.shuffle(order);
        int[] team = new int[np];
        for (int k = 0; k < np; k++) team[order.get(k)] = size == 0 ? -1 : k / size;
        int marcoId = rng.nextInt(np);
        Cls[] all = Cls.values();
        List<int[]> starts = startSquares(np);
        for (int i = 0; i < np; i++) {
            Cls c = classes != null ? classes[i] : all[rng.nextInt(all.length)];
            Player p = new Player(i, c, team[i], i == marcoId, rules.vigorMax);
            int[] s = starts.get(i);
            p.r = p.prevR = s[0]; p.c = p.prevC = s[1];
            players.add(p);
            if (p.isMarco) marco = p;
        }
    }

    private List<int[]> startSquares(int np) {
        List<int[]> out = new ArrayList<>();
        if (rules.startCorner) { for (int i = 0; i < np; i++) out.add(new int[]{0, 0}); return out; }
        List<int[]> per = perimeter();
        int offset = rng.nextInt(per.size());
        for (int i = 0; i < np; i++) out.add(per.get((offset + (i * per.size()) / np) % per.size()));
        List<int[]> shuffled = new ArrayList<>(out);
        rng.shuffle(shuffled);
        return shuffled;
    }

    List<int[]> perimeter() {
        List<int[]> p = new ArrayList<>();
        for (int c = 0; c < n; c++) p.add(new int[]{0, c});
        for (int r = 1; r < n; r++) p.add(new int[]{r, n - 1});
        for (int c = n - 2; c >= 0; c--) p.add(new int[]{n - 1, c});
        for (int r = n - 2; r >= 1; r--) p.add(new int[]{r, 0});
        return p;
    }

    private void setupSquares() {
        boolean[] taken = new boolean[n * n];
        for (Player p : players) taken[idx(p.r, p.c)] = true;
        for (int i = 0; i < n * n; i++) if (reserved[i]) taken[i] = true; // landmarks and marks stay plain
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < n * n; i++) if (!taken[i]) free.add(i);
        rng.shuffle(free);
        // contents: power-ups (exactly one Compass) and perils
        List<Content> pool = new ArrayList<>();
        Content[] pu = {Content.LEAP, Content.SHIELD, Content.SPYGLASS, Content.FALCON};
        Content[] pe = {Content.SERPENT, Content.DARKNESS, Content.FREEZE, Content.SCATTER};
        int nPu = rules.powerUpCount(), nPe = rules.perilCount();
        if (rules.simple) {
            pu = new Content[]{Content.LEAP, Content.SHIELD, Content.FALCON};
            pe = new Content[]{Content.FREEZE, Content.SCATTER, Content.DARKNESS};
            for (int i = 0; i < nPu; i++) pool.add(pu[i % pu.length]);
        } else for (int i = 0; i < nPu; i++) pool.add(i == 0 ? Content.COMPASS : pu[(i - 1) % pu.length]);
        for (int i = 0; i < nPe; i++) pool.add(pe[i % pe.length]);
        rng.shuffle(pool);
        int k = 0;
        // Simple Hunt: nothing is cast during the game, so every helper and danger is on the board from the start
        int filled = rules.simple ? pool.size() : Math.min(rules.filledAtStart(), pool.size());
        int need = filled + rules.slotCount() + rules.decoyCount() + 2;
        if (need > free.size()) throw new IllegalStateException("board too small for its decorations");
        for (int i = 0; i < filled; i++) content[free.get(k++)] = pool.get(i);
        for (int i = filled; i < pool.size(); i++) marco.hand.add(pool.get(i));
        if (!rules.simple) for (int i = 0; i < rules.slotCount(); i++) content[free.get(k++)] = Content.SLOT;
        for (int i = 0; i < rules.decoyCount(); i++) content[free.get(k++)] = Content.DECOY;
        oracle = free.get(k++);
        content[oracle] = Content.ORACLE;
        if (!rules.simple) spotlight = free.get(k++); // stays Content.NONE: hidden from everyone
        Content[] tasks = {Content.LEAP, Content.SHIELD, Content.SPYGLASS, Content.FALCON};
        oracleTaskItem = tasks[rng.nextInt(tasks.length)];
    }

    // ------------------------------------------------------------------ helpers

    public int idx(int r, int c) { return r * n + c; }
    public boolean onBoard(int r, int c) { return r >= 0 && c >= 0 && r < n && c < n; }
    public Player current() { return players.get(turnIndex); }
    public boolean decorated(int i) { return content[i] != Content.NONE; }
    public boolean unopenedDecorated(int i) { return decorated(i) && !opened[i]; }

    public List<Player> at(int r, int c) {
        List<Player> out = new ArrayList<>();
        for (Player p : players) if (p.r == r && p.c == c) out.add(p);
        return out;
    }

    public Band band(int d) {
        if (d <= rules.hotMax()) return Band.HOT;
        if (d <= rules.warmMax()) return Band.WARM;
        return Band.COLD;
    }

    /** What a player of this class reads for this distance (the Knight's warm reads as cold). */
    public Band reading(Cls cls, int d) {
        Band b = band(d);
        return (!rules.simple && cls == Cls.KNIGHT && b == Band.WARM) ? Band.COLD : b;
    }

    /** The player that signals currently point at: the newest active echo's decoy, else Marco. */
    public Player signalTarget() {
        Echo best = null;
        for (Echo e : echoes) if (e.start <= round && round < e.end && (best == null || e.start >= best.start)) best = e;
        return best == null ? marco : players.get(best.decoyId);
    }

    public boolean echoActive() { return signalTarget() != marco; }

    /** Squares reachable with exactly k orthogonal steps (turns allowed, never off the board). */
    public boolean reachable(Player p, int r, int c, int k) {
        if (!onBoard(r, c)) return false;
        int d = p.dist(r, c);
        return d <= k && (rules.walkUpTo || (k - d) % 2 == 0);
    }

    public int moveAllowance() { return moveRoll + moveExtra; }
    public int opportunitiesLeft() { return (int) Math.floor(budget + 1e-9) - used; }
    public boolean hasMoved() { return moved; }
    public boolean skillReady(Player p) { return round >= p.skillReadyAt; }

    private void spend() {
        if (opportunitiesLeft() <= 0) throw new IllegalStateException("no opportunity left this turn");
        used++;
    }

    private void needCurrent(Player p) {
        if (over) throw new IllegalStateException("game over");
        if (p != current()) throw new IllegalStateException("not " + p + "'s turn");
    }

    // ------------------------------------------------------------------ turn flow

    /** Starts the current player's turn. Returns false when the turn is skipped (Freeze); the game then moves on by itself. */
    public boolean beginTurn() {
        if (over) return false;
        Player p = current();
        moveRoll = 0; moveExtra = 0; used = 0; moved = false;
        if (p.frozenTurns > 0) {
            p.frozenTurns--;
            advance();
            return false;
        }
        int m = 1;
        if (p.team >= 0) for (Player q : players) if (p.teammateOf(q) && q.r == p.r && q.c == p.c) m++;
        together = rules.togetherBonus && m >= 2;
        if (together) budget = 1 + 0.25 * m + p.carry;
        else { budget = 1; p.carry = 0; }
        budget += p.bonusOpp;
        p.bonusOpp = 0;
        moveRoll = rollMove();
        return true;
    }

    /** For tests and the tutorial: replace this turn's move roll. */
    public void forceRoll(int k) { moveRoll = k; moveExtra = 0; }

    int rollMove() {
        int s = 0;
        for (int i = 0; i < rules.dieCount; i++) s += rng.die(rules.dieSides);
        return s;
    }

    public int rollStrength() { return rng.die(rules.strengthSides); }

    /** Walk to (r,c) with exactly the allowance; opens the square, finds Marco or starts a fight. */
    public void move(Player p, int r, int c) {
        needCurrent(p);
        if (moved) throw new IllegalStateException("already moved");
        if (!reachable(p, r, c, moveAllowance())) throw new IllegalArgumentException("not reachable: " + r + "," + c + " with " + moveAllowance());
        moved = true;
        int walked = p.dist(r, c);
        p.prevR = p.r; p.prevC = p.c;
        p.r = r; p.c = c;
        p.stepsWalked += walked; totalSteps += walked;
        arrive(p, true);
    }

    /** Stay a whole turn on the square: restores vigor. */
    public void rest(Player p) {
        needCurrent(p);
        if (moved) throw new IllegalStateException("already moved");
        moved = true;
        p.vigor = Math.min(rules.vigorMax, p.vigor + rules.restGain);
        tryOracle(p);
    }

    private void arrive(Player p, boolean landing) {
        int i = idx(p.r, p.c);
        final int lr = p.r, lc = p.c;
        checkErrand(p);
        if (over) return;
        if (p.isMarco && i == spotlight && !marcoRevealed) {
            marcoRevealed = true; revealRound = round;
            for (Listener l : listeners) l.onReveal(this, p);
        }
        // the square's content
        // a hidden Marco leaves no trace: squares he crosses stay closed
        if (unopenedDecorated(i) && !(rules.hiddenMarco && p.isMarco)) {
            opened[i] = true;
            Content ct = content[i];
            for (Listener l : listeners) l.onOpen(this, p, ct);
            if (ct.isPeril()) { if (castByMarco[i]) trapsFromMarco[p.id]++; else trapsFromLuck[p.id]++; }
            if (ct == Content.ORACLE) { opened[i] = true; oracleKnown = true; oracleFoundRound = round; }
            else if (ct.isPowerUp()) p.items.add(ct);
            else if (ct.isPeril()) applyPeril(p, ct);
        }
        if (over) return;
        // slid or thrown away by a peril: no encounter where the player did not stay
        if (p.r != lr || p.c != lc) return;
        // other travellers on the square
        if (landing && p.r == p.prevR && p.c == p.prevC) { tryOracle(p); return; }
        for (Player q : at(p.r, p.c)) {
            if (q == p || p.teammateOf(q)) continue;
            if (!rules.landingFinds) { if (rules.simple) steal(p, q); break; } // nothing is proven by landing
            if (q.isMarco) { finish(WinBy.LANDING, p); return; }
            // q is proven not to be Marco: the landing did not find him
            for (Listener l : listeners) l.onNotMarco(this, q);
            if (rules.simple) steal(p, q);
            else if (p.cls == Cls.TRAVELER && skillReady(p) && !p.items.isEmpty() && !q.items.isEmpty()) {
                // Trade: swap one power-up instead of fighting
                Content mine = p.items.remove(rng.nextInt(p.items.size()));
                Content theirs = q.items.remove(rng.nextInt(q.items.size()));
                p.items.add(theirs); q.items.add(mine);
                p.skillReadyAt = round + 2;
                trades++;
            } else {
                fight(p, q, false);
            }
            break; // one encounter per landing
        }
        if (!over) tryOracle(p);
    }

    /** Simple Hunt: the one thing that happens when you land on a traveller. */
    private void steal(Player thief, Player victim) {
        if (victim.items.remove(Content.SHIELD)) {
            stealsStopped++;
            for (Listener l : listeners) l.onSteal(this, thief, victim, null, true);
            return;
        }
        Content taken = null;
        if (!victim.items.isEmpty()) {
            taken = victim.items.remove(rng.nextInt(victim.items.size()));
            thief.items.add(taken);
            steals++;
        }
        for (Listener l : listeners) l.onSteal(this, thief, victim, taken, false);
    }

    private void applyPeril(Player p, Content peril) {
        if (p.items.remove(Content.SHIELD) || p.shieldUp) {
            p.shieldUp = false; perilsBlocked++;
            for (Listener l : listeners) l.onPeril(this, p, peril, 0, true);
            return;
        }
        if (rules.simple) {
            perilsHit++;
            int far = peril == Content.SCATTER ? rollStrength() : 1;
            for (Listener l : listeners) l.onPeril(this, p, peril, far, false);
            switch (peril) {
                case DARKNESS: p.hiddenSignals += 1; break;
                case FREEZE: p.frozenTurns = Math.max(p.frozenTurns, 1); break;
                case SCATTER: scatter(p, far); break;
                default: break;
            }
            return;
        }
        int s = Types.perilStrength(Types.perilFit(p.cls, peril), rollStrength());
        Player knight = bulwarkKnight(p);
        if (knight != null) { s = Math.max(0, s - 2); knight.skillReadyAt = round + 2; }
        if (s == 0) { perilsBlocked++; for (Listener l : listeners) l.onPeril(this, p, peril, 0, true); return; }
        perilsHit++;
        for (Listener l : listeners) l.onPeril(this, p, peril, s, false);
        p.vigor = Math.max(0, p.vigor - 1);
        switch (peril) {
            case SERPENT: slideBack(p, s); break;
            case DARKNESS: p.hiddenSignals += (s <= 3 ? 1 : 2); break;
            case FREEZE: p.frozenTurns = Math.max(p.frozenTurns, 1); break;
            case SCATTER: scatter(p, s); break;
            default: break;
        }
    }

    /** A Knight (p himself or a teammate on his square) whose Bulwark is ready, else null. */
    private Player bulwarkKnight(Player p) {
        for (Player q : at(p.r, p.c))
            if (q.cls == Cls.KNIGHT && (q == p || q.teammateOf(p)) && skillReady(q)) return q;
        return null;
    }

    /** Slide k squares back the way the player came (and on, if needed), stopping at the edge. Does not trigger squares. */
    void slideBack(Player p, int k) {
        int dr = Integer.signum(p.prevR - p.r), dc = Integer.signum(p.prevC - p.c);
        if (dr == 0 && dc == 0) { // no last move: slide toward the nearer edge's opposite (deterministic): toward row 0
            dr = p.r > 0 ? -1 : 1;
        }
        for (int s = 0; s < k; s++) {
            int remR = Math.abs(p.prevR - p.r), remC = Math.abs(p.prevC - p.c);
            boolean useRow = dr != 0 && (remR >= remC || dc == 0);
            int nr = p.r + (useRow ? dr : 0), nc = p.c + (useRow ? 0 : dc);
            if (!onBoard(nr, nc)) {
                // try the other axis, else stop
                nr = p.r + (useRow ? 0 : dr); nc = p.c + (useRow ? dc : 0);
                if (!onBoard(nr, nc) || (nr == p.r && nc == p.c)) break;
            }
            p.r = nr; p.c = nc;
        }
    }

    void scatter(Player p, int s) {
        List<int[]> opts = new ArrayList<>();
        for (int r = Math.max(0, p.r - s); r <= Math.min(n - 1, p.r + s); r++)
            for (int c = Math.max(0, p.c - s); c <= Math.min(n - 1, p.c + s); c++) {
                int d = p.dist(r, c);
                if (d >= 1 && d <= s && at(r, c).isEmpty()) opts.add(new int[]{r, c});
            }
        if (opts.isEmpty()) return;
        int[] t = opts.get(rng.nextInt(opts.size()));
        p.r = t[0]; p.c = t[1];
    }

    /** One fight. a is the mover or attacker. Returns the winner's id or -1. Never reveals Marco. */
    int fight(Player a, Player b, boolean onPurpose) {
        fights++;
        int ra = rng.die(rules.strengthSides) + fightBoost(a, true) + (onPurpose ? Types.strikeBonus(a.cls) : 0);
        int rb = rng.die(rules.strengthSides) + fightBoost(b, false);
        boolean canStrikeBack = !onPurpose || a.dist(b) <= Types.reach(b.cls);
        int winner = -1;
        if (ra > rb) { winFight(a, b, ra - rb); winner = a.id; }
        else if (rb > ra && canStrikeBack) { winFight(b, a, rb - ra); winner = b.id; }
        for (Listener l : listeners) l.onFight(this, a, b, winner);
        return winner;
    }

    /** +2 when the side plays something that suits its class: the Knight's Bulwark when defending, or a fitting power-up. */
    private int fightBoost(Player p, boolean attacking) {
        if (!attacking && p.cls == Cls.KNIGHT && skillReady(p)) { p.skillReadyAt = round + 2; return 2; }
        for (int i = 0; i < p.items.size(); i++) {
            Content it = p.items.get(i);
            if ((it == Content.LEAP || it == Content.SHIELD) && Types.suits(p.cls, it, rules.secondItems)) {
                p.items.remove(i);
                return 2;
            }
        }
        return 0;
    }

    private void winFight(Player w, Player l, int diff) {
        if (!l.items.isEmpty()) w.items.add(l.items.remove(rng.nextInt(l.items.size())));
        else w.bonusOpp++;
        // the loser slides back by the difference, away from the winner's side
        if (l.r == w.r && l.c == w.c) slideBack(l, diff);
        else {
            int dr = Integer.signum(l.r - w.r), dc = Integer.signum(l.c - w.c);
            for (int s = 0; s < diff; s++) {
                int nr = l.r + (Math.abs(l.r - w.r) >= Math.abs(l.c - w.c) ? dr : 0);
                int nc = l.c + (Math.abs(l.r - w.r) >= Math.abs(l.c - w.c) ? 0 : dc);
                if (!onBoard(nr, nc)) break;
                l.r = nr; l.c = nc;
            }
        }
        l.vigor = Math.max(0, l.vigor - 1);
    }

    // ------------------------------------------------------------------ actions (each costs one opportunity unless noted)

    /** Attack a non-teammate within reach. */
    public int attack(Player a, Player b) {
        needCurrent(a);
        if (a.teammateOf(b) || a == b) throw new IllegalArgumentException("not an enemy");
        if (a.dist(b) > Types.reach(a.cls)) throw new IllegalArgumentException("out of reach");
        spend();
        return fight(a, b, true);
    }

    /** Mongol's Volley: two attacks within reach 3 for one opportunity. */
    public void volley(Player a, Player b1, Player b2) {
        needCurrent(a);
        if (a.cls != Cls.MONGOL || !skillReady(a)) throw new IllegalStateException("no volley");
        spend();
        a.skillReadyAt = round + 2;
        for (Player b : new Player[]{b1, b2}) {
            if (b == null || over) continue;
            if (a.teammateOf(b) || a == b || a.dist(b) > 3) continue;
            fight(a, b, true);
        }
    }

    /** Magus's Divination: which of two squares is nearer to Marco (fooled by an echo). */
    public int divination(Player p, int ar, int ac, int br, int bc) {
        needCurrent(p);
        if (p.cls != Cls.MAGUS || !skillReady(p)) throw new IllegalStateException("no divination");
        if (rules.divinationNeedsEmptySquares && (!at(ar, ac).isEmpty() || !at(br, bc).isEmpty()))
            throw new IllegalArgumentException("name empty squares");
        spend();
        p.skillReadyAt = round + 2;
        Player t = signalTarget();
        int da = t.dist(ar, ac), db = t.dist(br, bc);
        int ans = Integer.compare(da, db);
        for (Listener l : listeners) l.onDivination(this, p, ar, ac, br, bc, ans);
        return ans;
    }

    /** Leap: extra squares this turn equal to its strength (+2 if it suits). Use before moving. */
    public int useLeap(Player p) {
        needCurrent(p);
        if (moved || !p.items.remove(Content.LEAP)) throw new IllegalStateException("no leap");
        spend();
        int s = rollStrength() + (!rules.simple && Types.suits(p.cls, Content.LEAP, rules.secondItems) ? 2 : 0);
        moveExtra += s;
        return s;
    }

    public void useSpyglass(Player p) {
        needCurrent(p);
        if (!p.items.remove(Content.SPYGLASS)) throw new IllegalStateException("no spyglass");
        spend();
        boolean found = false;
        for (int i = echoes.size() - 1; i >= 0; i--) {
            Echo e = echoes.get(i);
            if (e.start <= round && round < e.end) { echoes.remove(i); found = true; }
        }
        if (found) echoesDetected++;
        for (Listener l : listeners) l.onSpyglass(this, p, found);
    }

    public Dir useFalcon(Player p) {
        needCurrent(p);
        if (!p.items.remove(Content.FALCON)) throw new IllegalStateException("no falcon");
        spend();
        Player t = signalTarget();
        int dr = t.r - p.r, dc = t.c - p.c;
        Dir d;
        if (Math.abs(dr) > Math.abs(dc)) d = dr < 0 ? Dir.N : Dir.S;
        else if (Math.abs(dc) > Math.abs(dr)) d = dc > 0 ? Dir.E : Dir.W;
        else if (dr == 0) d = Dir.values()[rng.nextInt(4)]; // same square (only possible for a teammate of Marco)
        else d = rng.nextInt(2) == 0 ? (dr < 0 ? Dir.N : Dir.S) : (dc > 0 ? Dir.E : Dir.W);
        for (Listener l : listeners) l.onFalcon(this, p, d);
        return d;
    }

    public int useCompass(Player p) {
        needCurrent(p);
        if (!p.items.remove(Content.COMPASS)) throw new IllegalStateException("no compass");
        spend();
        Player t = signalTarget();
        boolean rows = rng.nextInt(2) == 0;
        int v = rows ? Math.abs(t.r - p.r) : Math.abs(t.c - p.c);
        for (Listener l : listeners) l.onCompass(this, p, rows, v);
        return v;
    }

    /** Raise a Shield now (it also works automatically from the sac). */
    public void useShield(Player p) {
        needCurrent(p);
        if (!p.items.remove(Content.SHIELD)) throw new IllegalStateException("no shield");
        spend();
        p.shieldUp = true;
    }

    /** Give a power-up to a teammate on the same square (free). */
    public void give(Player from, Player to, Content item) {
        if (!from.teammateOf(to) || from.r != to.r || from.c != to.c) throw new IllegalArgumentException("not together");
        if (!from.items.remove(item)) throw new IllegalArgumentException("no such item");
        to.items.add(item);
    }

    /** One accusation per player per game (free). Returns true if right. */
    public boolean accuse(Player p, Player q) {
        needCurrent(p);
        if (!rules.accusations || marcoRevealed) throw new IllegalStateException("no accusations now");
        if (p.accused) throw new IllegalStateException("already accused");
        if (p.isMarco) throw new IllegalStateException("Marco cannot accuse");
        p.accused = true;
        if (q.isMarco) { finish(WinBy.ACCUSATION, p); return true; }
        wrongAccusations++;
        if (rules.wrongAccuseTurns > 0) p.frozenTurns = Math.max(p.frozenTurns, rules.wrongAccuseTurns);
        for (Listener l : listeners) l.onWrongAccusation(this, p, q);
        return false;
    }

    /** Marco casts one item from his hand into an empty, unoccupied slot (free, once per turn). */
    int lastCastRound = -1;
    public void cast(Player m, int square, Content item) {
        needCurrent(m);
        if (!m.isMarco) throw new IllegalStateException("only Marco casts");
        if (lastCastRound == round) throw new IllegalStateException("one cast per turn");
        if (content[square] != Content.SLOT || opened[square]) throw new IllegalArgumentException("not a free slot");
        if (!at(square / n, square % n).isEmpty()) throw new IllegalArgumentException("occupied");
        if (!m.hand.remove(item)) throw new IllegalArgumentException("not in hand");
        content[square] = item;
        castByMarco[square] = true;
        lastCastRound = round;
        casts++;
    }

    public boolean canEcho(Player m) {
        if (!m.isMarco || marcoRevealed) return false;
        if (rules.echoCharges >= 0 && m.echoesStarted >= rules.echoCharges) return false;
        return round - m.lastEchoTurn >= rules.echoEvery;
    }

    /** Marco sets an echo: after `delay` rounds, signals point at `decoy` for `length` rounds (free). */
    public void setEcho(Player m, Player decoy, int delay, int length) {
        needCurrent(m);
        if (!canEcho(m)) throw new IllegalStateException("no echo now");
        if (decoy == m || length < 1 || delay < 0) throw new IllegalArgumentException("bad echo");
        int start = round + delay;
        echoes.add(new Echo(start, start + length, decoy.id));
        m.echoesStarted++; m.lastEchoTurn = round; echoesSet++;
    }

    private void tryOracle(Player p) {
        if (oracle < 0 || !oracleKnown || idx(p.r, p.c) != oracle || over) return;
        List<Player> team = new ArrayList<>();
        if (p.team < 0) team.add(p);
        else for (Player q : players) if (q.team == p.team) team.add(q);
        int key = p.team < 0 ? 8 + p.id : p.team;
        if (key < teamAskedOracle.length && teamAskedOracle[key]) return;
        List<Player> there = new ArrayList<>();
        for (Player q : team) if (idx(q.r, q.c) == oracle) there.add(q);
        int need = rules.oracleQuorum <= 0 ? team.size() : (int) Math.ceil(rules.oracleQuorum * team.size());
        if (there.size() < need) return;
        team = there; // the incantation is made by those present
        boolean fit = true;
        if (!rules.simple) for (Player q : team) if (q.vigor < rules.vigorMax) fit = false;
        if (!fit) {
            Player holder = null;
            for (Player q : team) if (q.items.contains(oracleTaskItem)) { holder = q; break; }
            if (holder == null) return;
            holder.items.remove(oracleTaskItem);
        }
        if (key < teamAskedOracle.length) teamAskedOracle[key] = true;
        oracleAnswers++;
        if (firstOracleRound < 0) firstOracleRound = round;
        // "Near X there is Y where Marco used to be; now he is about d squares from it."
        int[] was = marcoTrail.size() >= 2 ? marcoTrail.get(marcoTrail.size() - 2) : new int[]{marco.r, marco.c};
        int best = 0;
        for (int i = 1; i < marks.length; i++)
            if (Math.abs(marks[i][0] - was[0]) + Math.abs(marks[i][1] - was[1]) < Math.abs(marks[best][0] - was[0]) + Math.abs(marks[best][1] - was[1])) best = i;
        int d = marco.dist(marks[best][0], marks[best][1]);
        int about = Math.max(0, d + rng.nextInt(2 * rules.oracleTolerance + 1) - rules.oracleTolerance);
        for (Player q : team) for (Listener l : listeners) l.onOracle(this, q, best, about);
    }

    /** Ends the turn: the private temperature signal, then the next player. */
    public void endTurn(Player p) {
        needCurrent(p);
        if (!moved) throw new IllegalStateException("move or rest first");
        // saved part of the team opportunities: kept only while together, at most one (Chat's open point, CC's choice)
        p.carry = together ? Math.min(1.0, Math.max(0, budget - used)) : 0;
        if (!p.isMarco) {
            if (p.hiddenSignals > 0) { p.hiddenSignals--; for (Listener l : listeners) l.onSignalHidden(this, p); }
            else {
                int[] t = pastPos(signalTarget(), rules.signalLag);
                Band b = reading(p.cls, p.dist(t[0], t[1]));
                for (Listener l : listeners) l.onSignal(this, p, b);
            }
        }
        advance();
    }

    private void advance() {
        if (over) return;
        turnIndex++;
        if (turnIndex >= players.size()) {
            turnIndex = 0;
            stepsByRound.add(totalSteps);
            marcoTrail.add(new int[]{marco.r, marco.c});
            if (rules.signalLag > 0) {
                int[][] snap = new int[players.size()][];
                for (Player q : players) snap[q.id] = new int[]{q.r, q.c};
                history.add(snap);
            }
            if (rules.turnLimit > 0 && round >= rules.turnLimit) { finish(WinBy.TIME, null); return; }
            round++;
            if (round > rules.maxRounds) { finish(WinBy.TIME, null); }
        }
    }

    private void finish(WinBy by, Player who) {
        if (over) return;
        over = true; winBy = by; finder = who;
        if (stepsByRound.size() < round) stepsByRound.add(totalSteps);
        for (Listener l : listeners) l.onOver(this);
    }

    /** Rule checks used by the tests and the thousand-game run. Returns null if all is well. */
    public String checkInvariants() {
        int compass = 0;
        for (int i = 0; i < content.length; i++) if (content[i] == Content.COMPASS && !opened[i]) compass++;
        for (Player p : players) {
            if (!onBoard(p.r, p.c)) return p + " off the board";
            if (p.vigor < 0 || p.vigor > rules.vigorMax) return p + " vigor " + p.vigor;
            for (Content it : p.items) { if (!it.isPowerUp()) return p + " holds " + it; if (it == Content.COMPASS) compass++; }
            for (Content it : p.hand) if (it == Content.COMPASS) compass++;
            if (!p.isMarco && !p.hand.isEmpty()) return p + " has a hand";
        }
        if (compass > 1) return "more than one compass";
        for (Echo e : echoes) if (e.decoyId == marco.id) return "echo points at Marco";
        if (over && winBy == WinBy.NONE) return "over without result";
        if (winBy == WinBy.LANDING || winBy == WinBy.ACCUSATION) {
            if (finder == null || finder.isMarco) return "bad finder";
            if (winBy == WinBy.LANDING && finder.teammateOf(marco)) return "teammate found Marco by landing";
        }
        int marcos = 0;
        for (Player p : players) if (p.isMarco) marcos++;
        if (marcos != 1) return "marcos " + marcos;
        return null;
    }
}
