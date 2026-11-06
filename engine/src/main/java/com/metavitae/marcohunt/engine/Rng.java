package com.metavitae.marcohunt.engine;

/** Small deterministic random generator (xorshift64*), identical on every JVM and on Android. */
public final class Rng {
    private long s;

    public Rng(long seed) { s = seed == 0 ? 0x9E3779B97F4A7C15L : seed; for (int i = 0; i < 4; i++) next(); }

    public long next() {
        s ^= s >>> 12; s ^= s << 25; s ^= s >>> 27;
        return s * 0x2545F4914F6CDD1DL;
    }

    /** 0 .. bound-1 */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound " + bound);
        return (int) Long.remainderUnsigned(next(), bound);
    }

    public double nextDouble() { return (next() >>> 11) * 0x1.0p-53; }

    /** One die with this many sides: 1 .. sides */
    public int die(int sides) { return 1 + nextInt(sides); }

    public <T> void shuffle(java.util.List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T t = list.get(i); list.set(i, list.get(j)); list.set(j, t);
        }
    }
}
