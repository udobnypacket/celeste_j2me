import java.io.InputStream;
import java.util.Random;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;

/**
 * Celeste Classic (PICO-8) port for J2ME. No sound.
 * Logic is a literal port of the original Lua; rendering is scaled 15/8
 * (128 -> 240 px), so one 8x8 tile = 15x15 px.
 *
 * Keys: d-pad / 2468 = move, 1 or 7 = jump, 3 or 9 or 5 = dash,
 * right softkey = exit.
 */
public class Game extends GameCanvas implements Runnable {

    // ---- object types ----
    static final int T_PLAYER = 1, T_SPAWN = 2, T_SPRING = 3, T_BALLOON = 4,
            T_FALL = 5, T_SMOKE = 6, T_FRUIT = 7, T_FLYFRUIT = 8, T_LIFEUP = 9,
            T_FAKEWALL = 10, T_KEY = 11, T_CHEST = 12, T_PLATFORM = 13,
            T_MESSAGE = 14, T_BIGCHEST = 15, T_ORB = 16, T_FLAG = 17,
            T_ROOMTITLE = 18;

    static final int[] PAL = {
        0xFF000000, 0xFF1D2B53, 0xFF7E2553, 0xFF008751,
        0xFFAB5236, 0xFF5F574F, 0xFFC2C3C7, 0xFFFFF1E8,
        0xFFFF004D, 0xFFFFA300, 0xFFFFEC27, 0xFF00E436,
        0xFF29ADFF, 0xFF83769C, 0xFFFF77A8, 0xFFFFCCAA
    };

    static final String MSG = "-- CELESTE MOUNTAIN --#THIS MEMORIAL TO THOSE# PERISHED ON THE CLIMB";

    // ---- game object ----
    static final class Obj {
        int type;
        boolean dead;
        boolean collideable = true, solids = true;
        float x, y, spdx, spdy, remx, remy;
        float hx = 0, hy = 0, hw = 8, hh = 8;
        boolean flipx, flipy;
        float spr;
        // generic
        int state, delay, timer;
        float offset, start, step, last, duration, flash;
        boolean fly, show;
        int dir, index, lastIdx, score;
        // player
        boolean pJump, pDash, wasOnGround;
        int grace, jbuffer, djump, dashTime, dashEffectTime, hideIn, hideFor;
        float dashTx, dashTy, dashAx, dashAy, sprOff;
        float targetX, targetY;
        float[] hairX, hairY;
        int[] hairS;
        // big chest particles
        float[] pX, pY, pH, pSpd;
        int pCount;
    }

    // ---- data ----
    byte[] data;
    static final int OFF_MAP = 4096, OFF_FLAGS = 12288;
    Image sheet, sheetCur, flashSheet;
    int flashSig = -1;
    Image hair12, hair7, hair11;
    int[] palMap = new int[16];

    // ---- screen ----
    int ox, oy;           // top-left of game area on screen
    int camX, camY;       // screen-shake offset in screen px
    Graphics g;
    Font font;

    // ---- state ----
    Obj[] objs = new Obj[160];
    Obj[] tmp = new Obj[160];
    int n = 0;
    int roomX, roomY;
    int freeze, shake, delayRestart;
    boolean willRestart, hasDashed, hasKey, pausePlayer, flashBg, newBg;
    boolean[] gotFruit = new boolean[33];
    int frames, seconds, minutes, deaths, maxDjump;
    boolean startGame;
    int startGameFlash;

    // clouds / particles
    float[] cX = new float[17], cY = new float[17], cSpd = new float[17], cW = new float[17];
    float[] pX = new float[25], pY = new float[25], pSpd = new float[25], pOff = new float[25];
    int[] pS = new int[25], pC = new int[25];
    // dead particles
    float[] dX = new float[8], dY = new float[8], dSx = new float[8], dSy = new float[8];
    int[] dT = new int[8];

    // map layer caches
    int[][] lx = new int[3][256], ly = new int[3][256], lid = new int[3][256];
    int[] lc = new int[3];

    // input
    boolean kL, kR, kU, kD, kJump, kDash;

    Random rand = new Random();
    CelesteMIDlet midlet;
    volatile boolean running, paused;
    Thread thread;

    public Game(CelesteMIDlet m) {
        super(false);
        midlet = m;
        setFullScreenMode(true);
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        ox = (getWidth() - 240) / 2;
        oy = (getHeight() - 240) / 2;
        loadData();
        for (int i = 0; i < 16; i++) palMap[i] = i;
        sheet = buildImage(16, 8, palMap);
        sheetCur = sheet;
        int[] m2 = new int[16];
        for (int i = 0; i < 16; i++) m2[i] = i;
        m2[8] = 12; hair12 = buildImage(8, 1, m2);
        m2[8] = 7;  hair7 = buildImage(8, 1, m2);
        m2[8] = 11; hair11 = buildImage(8, 1, m2);
        initEffects();
        titleScreen();
    }

    // ================= lifecycle =================
    void start() {
        paused = false;
        if (thread == null) {
            running = true;
            thread = new Thread(this);
            thread.start();
        }
    }

    void pause() { paused = true; }

    void stop() {
        running = false;
        thread = null;
    }

    public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            if (!paused) {
                pollKeys();
                update();
                draw();
            }
            long dt = System.currentTimeMillis() - t0;
            long sl = 33 - dt;
            if (sl < 1) sl = 1;
            try {
                Thread.sleep(sl);
            } catch (InterruptedException e) {
            }
        }
    }

    protected void keyPressed(int keyCode) {
        if (keyCode == -7) midlet.exit();
    }

    protected void hideNotify() { paused = true; }
    protected void showNotify() { paused = false; }

    void pollKeys() {
        int s = getKeyStates();
        kL = (s & LEFT_PRESSED) != 0;
        kR = (s & RIGHT_PRESSED) != 0;
        kU = (s & UP_PRESSED) != 0;
        kD = (s & DOWN_PRESSED) != 0;
        kJump = (s & (GAME_A_PRESSED | GAME_C_PRESSED)) != 0;
        kDash = (s & (GAME_B_PRESSED | GAME_D_PRESSED | FIRE_PRESSED)) != 0;
    }

    // ================= data =================
    void loadData() {
        data = new byte[12416];
        try {
            InputStream is = getClass().getResourceAsStream("/data.bin");
            int off = 0;
            while (off < data.length) {
                int r = is.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            is.close();
        } catch (Exception e) {
        }
    }

    int mget(int x, int y) {
        if (x < 0 || y < 0 || x > 127 || y > 63) return 0;
        return data[OFF_MAP + y * 128 + x] & 0xFF;
    }

    boolean fget(int tile, int bit) {
        if (tile > 127) return false;
        return ((data[OFF_FLAGS + tile] >> bit) & 1) != 0;
    }

    int fgetAll(int tile) {
        if (tile > 127) return 0;
        return data[OFF_FLAGS + tile] & 0xFF;
    }

    // scaled sprite image: cols x rows tiles of 8x8 -> 15x15 each
    Image buildImage(int cols, int rows, int[] map) {
        int w = cols * 15, h = rows * 15;
        int[] px = new int[w * h];
        for (int dy = 0; dy < h; dy++) {
            int sy = (16 * dy + 8) / 30;
            for (int dx = 0; dx < w; dx++) {
                int sx = (16 * dx + 8) / 30;
                int v = (data[sy * 64 + (sx >> 1)] >> ((sx & 1) * 4)) & 15;
                px[dy * w + dx] = (v == 0) ? 0 : PAL[map[v]];
            }
        }
        return Image.createRGBImage(px, w, h, true);
    }

    // ================= math helpers =================
    float rnd(float m) {
        return ((rand.nextInt() >>> 8) / 16777216f) * m;
    }

    static float sin(float t) { return (float) -Math.sin(t * 6.2831853f); }
    static float cos(float t) { return (float) Math.cos(t * 6.2831853f); }
    static float flr(float v) { return (float) Math.floor(v); }
    static int ifl(float v) { return (int) Math.floor(v); }
    static float sign(float v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
    static float fmod(float a, float b) { return a - (float) Math.floor(a / b) * b; }
    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    static float appr(float val, float target, float amount) {
        return val > target ? Math.max(val - amount, target) : Math.min(val + amount, target);
    }

    boolean maybe() { return rnd(1) < 0.5f; }

    int levelIndex() { return roomX % 8 + roomY * 8; }
    boolean isTitle() { return levelIndex() == 31; }

    // ================= init =================
    void initEffects() {
        for (int i = 0; i <= 16; i++) {
            cX[i] = rnd(128);
            cY[i] = rnd(128);
            cSpd[i] = 1 + rnd(4);
            cW[i] = 32 + rnd(32);
        }
        for (int i = 0; i <= 24; i++) {
            pX[i] = rnd(128);
            pY[i] = rnd(128);
            pS[i] = 0 + ifl(rnd(5) / 4);
            pSpd[i] = 0.25f + rnd(5);
            pOff[i] = rnd(1);
            pC[i] = 6 + ifl(0.5f + rnd(1));
        }
    }

    void titleScreen() {
        for (int i = 0; i < 33; i++) gotFruit[i] = false;
        frames = 0;
        deaths = 0;
        maxDjump = 1;
        startGame = false;
        startGameFlash = 0;
        loadRoom(7, 3);
    }

    void beginGame() {
        frames = 0;
        seconds = 0;
        minutes = 0;
        startGame = false;
        loadRoom(0, 0);
    }

    // ================= objects =================
    Obj initObject(int type, float x, float y) {
        if ((type == T_FRUIT || type == T_FLYFRUIT || type == T_FAKEWALL
                || type == T_KEY || type == T_CHEST) && gotFruit[1 + levelIndex()]) {
            return null;
        }
        if (n >= objs.length) return null;
        Obj o = new Obj();
        o.type = type;
        o.x = x;
        o.y = y;
        switch (type) {
            case T_SPAWN: o.spr = 1; break;
            case T_SPRING: o.spr = 18; break;
            case T_BALLOON: o.spr = 22; break;
            case T_FALL: o.spr = 23; break;
            case T_FRUIT: o.spr = 26; break;
            case T_FLYFRUIT: o.spr = 28; break;
            case T_FAKEWALL: o.spr = 64; break;
            case T_KEY: o.spr = 8; break;
            case T_CHEST: o.spr = 20; break;
            case T_MESSAGE: o.spr = 86; break;
            case T_BIGCHEST: o.spr = 96; break;
            case T_FLAG: o.spr = 118; break;
            default: o.spr = 0;
        }
        objs[n++] = o;
        initType(o);
        return o;
    }

    void initType(Obj t) {
        switch (t.type) {
            case T_PLAYER:
                t.pJump = false;
                t.pDash = false;
                t.grace = 0;
                t.jbuffer = 0;
                t.djump = maxDjump;
                t.dashTime = 0;
                t.dashEffectTime = 0;
                t.hx = 1; t.hy = 3; t.hw = 6; t.hh = 5;
                t.sprOff = 0;
                t.wasOnGround = false;
                createHair(t);
                break;
            case T_SPAWN:
                t.spr = 3;
                t.targetX = t.x;
                t.targetY = t.y;
                t.y = 128;
                t.spdy = -4;
                t.state = 0;
                t.delay = 0;
                t.solids = false;
                createHair(t);
                break;
            case T_SPRING:
                t.hideIn = 0;
                t.hideFor = 0;
                break;
            case T_BALLOON:
                t.offset = rnd(1);
                t.start = t.y;
                t.timer = 0;
                t.hx = -1; t.hy = -1; t.hw = 10; t.hh = 10;
                break;
            case T_FALL:
                t.state = 0;
                break;
            case T_SMOKE:
                t.spr = 29;
                t.spdy = -0.1f;
                t.spdx = 0.3f + rnd(0.2f);
                t.x += -1 + rnd(2);
                t.y += -1 + rnd(2);
                t.flipx = maybe();
                t.flipy = maybe();
                t.solids = false;
                break;
            case T_FRUIT:
                t.start = t.y;
                t.offset = 0;
                break;
            case T_FLYFRUIT:
                t.start = t.y;
                t.fly = false;
                t.step = 0.5f;
                t.solids = false;
                break;
            case T_LIFEUP:
                t.spdy = -0.25f;
                t.duration = 30;
                t.x -= 2;
                t.y -= 4;
                t.flash = 0;
                t.solids = false;
                break;
            case T_CHEST:
                t.x -= 4;
                t.start = t.x;
                t.timer = 20;
                break;
            case T_PLATFORM:
                t.x -= 4;
                t.solids = false;
                t.hw = 16;
                t.last = t.x;
                break;
            case T_MESSAGE:
                t.index = 0;
                t.lastIdx = 0;
                break;
            case T_BIGCHEST:
                t.state = 0;
                t.hw = 16;
                break;
            case T_ORB:
                t.spdy = -4;
                t.solids = false;
                break;
            case T_FLAG:
                t.x += 5;
                t.score = 0;
                t.show = false;
                for (int i = 0; i < 33; i++) if (gotFruit[i]) t.score++;
                break;
            case T_ROOMTITLE:
                t.delay = 5;
                break;
            default:
        }
    }

    void destroyObject(Obj o) {
        if (o.dead) return;
        o.dead = true;
        for (int i = 0; i < n; i++) {
            if (objs[i] == o) {
                for (int j = i; j < n - 1; j++) objs[j] = objs[j + 1];
                objs[--n] = null;
                return;
            }
        }
    }

    void createHair(Obj o) {
        o.hairX = new float[5];
        o.hairY = new float[5];
        o.hairS = new int[5];
        for (int i = 0; i < 5; i++) {
            o.hairX[i] = o.x;
            o.hairY[i] = o.y;
            o.hairS[i] = Math.max(1, Math.min(2, 3 - i));
        }
    }

    // ---- collision ----
    Obj collide(Obj o, int type, float ox_, float oy_) {
        for (int i = 0; i < n; i++) {
            Obj p = objs[i];
            if (p != o && p.type == type && p.collideable
                    && p.x + p.hx + p.hw > o.x + o.hx + ox_
                    && p.y + p.hy + p.hh > o.y + o.hy + oy_
                    && p.x + p.hx < o.x + o.hx + o.hw + ox_
                    && p.y + p.hy < o.y + o.hy + o.hh + oy_) {
                return p;
            }
        }
        return null;
    }

    boolean check(Obj o, int type, float ox_, float oy_) {
        return collide(o, type, ox_, oy_) != null;
    }

    boolean isSolid(Obj o, float ox_, float oy_) {
        if (oy_ > 0 && !check(o, T_PLATFORM, ox_, 0) && check(o, T_PLATFORM, ox_, oy_)) {
            return true;
        }
        return solidAt(o.x + o.hx + ox_, o.y + o.hy + oy_, o.hw, o.hh)
                || check(o, T_FALL, ox_, oy_)
                || check(o, T_FAKEWALL, ox_, oy_);
    }

    boolean isIce(Obj o, float ox_, float oy_) {
        return iceAt(o.x + o.hx + ox_, o.y + o.hy + oy_, o.hw, o.hh);
    }

    void move(Obj o, float mx, float my) {
        o.remx += mx;
        int amount = ifl(o.remx + 0.5f);
        o.remx -= amount;
        moveX(o, amount, 0);
        o.remy += my;
        amount = ifl(o.remy + 0.5f);
        o.remy -= amount;
        moveY(o, amount);
    }

    void moveX(Obj o, int amount, int start) {
        if (o.solids) {
            int step = amount > 0 ? 1 : (amount < 0 ? -1 : 0);
            int a = Math.abs(amount);
            for (int i = start; i <= a; i++) {
                if (!isSolid(o, step, 0)) {
                    o.x += step;
                } else {
                    o.spdx = 0;
                    o.remx = 0;
                    break;
                }
            }
        } else {
            o.x += amount;
        }
    }

    void moveY(Obj o, int amount) {
        if (o.solids) {
            int step = amount > 0 ? 1 : (amount < 0 ? -1 : 0);
            int a = Math.abs(amount);
            for (int i = 0; i <= a; i++) {
                if (!isSolid(o, 0, step)) {
                    o.y += step;
                } else {
                    o.spdy = 0;
                    o.remy = 0;
                    break;
                }
            }
        } else {
            o.y += amount;
        }
    }

    // ---- tiles ----
    int tileAt(int x, int y) { return mget(roomX * 16 + x, roomY * 16 + y); }

    boolean solidAt(float x, float y, float w, float h) { return tileFlagAt(x, y, w, h, 0); }
    boolean iceAt(float x, float y, float w, float h) { return tileFlagAt(x, y, w, h, 4); }

    boolean tileFlagAt(float x, float y, float w, float h, int flag) {
        int i0 = Math.max(0, ifl(x / 8)), i1 = Math.min(15, ifl((x + w - 1) / 8));
        int j0 = Math.max(0, ifl(y / 8)), j1 = Math.min(15, ifl((y + h - 1) / 8));
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                if (fget(tileAt(i, j), flag)) return true;
            }
        }
        return false;
    }

    boolean spikesAt(float x, float y, float w, float h, float xs, float ys) {
        int i0 = Math.max(0, ifl(x / 8)), i1 = Math.min(15, ifl((x + w - 1) / 8));
        int j0 = Math.max(0, ifl(y / 8)), j1 = Math.min(15, ifl((y + h - 1) / 8));
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                int tile = tileAt(i, j);
                if (tile == 17 && (fmod(y + h - 1, 8) >= 6 || y + h == j * 8 + 8) && ys >= 0) {
                    return true;
                } else if (tile == 27 && fmod(y, 8) <= 2 && ys <= 0) {
                    return true;
                } else if (tile == 43 && fmod(x, 8) <= 2 && xs <= 0) {
                    return true;
                } else if (tile == 59 && (fmod(x + w - 1, 8) >= 6 || x + w == i * 8 + 8) && xs >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================= rooms =================
    void killPlayer(Obj o) {
        deaths++;
        shake = 10;
        destroyObject(o);
        for (int dir = 0; dir < 8; dir++) {
            float angle = dir / 8f;
            dX[dir] = o.x + 4;
            dY[dir] = o.y + 4;
            dT[dir] = 10;
            dSx[dir] = sin(angle) * 3;
            dSy[dir] = cos(angle) * 3;
        }
        willRestart = true;
        delayRestart = 15;
    }

    void nextRoom() {
        if (roomX == 7) loadRoom(0, roomY + 1);
        else loadRoom(roomX + 1, roomY);
    }

    void loadRoom(int x, int y) {
        hasDashed = false;
        hasKey = false;
        for (int i = 0; i < n; i++) {
            objs[i].dead = true;
            objs[i] = null;
        }
        n = 0;
        roomX = x;
        roomY = y;
        lc[0] = lc[1] = lc[2] = 0;
        for (int tx = 0; tx < 16; tx++) {
            for (int ty = 0; ty < 16; ty++) {
                int tile = mget(roomX * 16 + tx, roomY * 16 + ty);
                if (tile == 0) continue;
                // map layers (mask 4, 2, 8)
                int fl = fgetAll(tile);
                for (int L = 0; L < 3; L++) {
                    int mask = (L == 0) ? 4 : (L == 1 ? 2 : 8);
                    if ((fl & mask) == mask) {
                        int k = lc[L]++;
                        lx[L][k] = tx * 8;
                        ly[L][k] = ty * 8;
                        lid[L][k] = tile;
                    }
                }
                // entities
                if (tile == 11) {
                    Obj p = initObject(T_PLATFORM, tx * 8, ty * 8);
                    if (p != null) p.dir = -1;
                } else if (tile == 12) {
                    Obj p = initObject(T_PLATFORM, tx * 8, ty * 8);
                    if (p != null) p.dir = 1;
                } else {
                    int type = 0;
                    switch (tile) {
                        case 1: type = T_SPAWN; break;
                        case 18: type = T_SPRING; break;
                        case 22: type = T_BALLOON; break;
                        case 23: type = T_FALL; break;
                        case 26: type = T_FRUIT; break;
                        case 28: type = T_FLYFRUIT; break;
                        case 64: type = T_FAKEWALL; break;
                        case 8: type = T_KEY; break;
                        case 20: type = T_CHEST; break;
                        case 86: type = T_MESSAGE; break;
                        case 96: type = T_BIGCHEST; break;
                        case 118: type = T_FLAG; break;
                        default:
                    }
                    if (type != 0) initObject(type, tx * 8, ty * 8);
                }
            }
        }
        if (!isTitle()) initObject(T_ROOMTITLE, 0, 0);
    }

    // ================= update =================
    void update() {
        frames = (frames + 1) % 30;
        if (frames == 0 && levelIndex() < 30) {
            seconds = (seconds + 1) % 60;
            if (seconds == 0) minutes++;
        }

        if (freeze > 0) {
            freeze--;
            return;
        }

        if (shake > 0) {
            shake--;
            camX = 0;
            camY = 0;
            if (shake > 0) {
                camX = ifl((2 - rnd(5)) * 1.875f);
                camY = ifl((2 - rnd(5)) * 1.875f);
            }
        }

        if (willRestart && delayRestart > 0) {
            delayRestart--;
            if (delayRestart <= 0) {
                willRestart = false;
                loadRoom(roomX, roomY);
            }
        }

        int cnt = n;
        for (int i = 0; i < cnt; i++) tmp[i] = objs[i];
        for (int i = 0; i < cnt; i++) {
            Obj o = tmp[i];
            if (o == null || o.dead) continue;
            move(o, o.spdx, o.spdy);
            updateObj(o);
        }
        for (int i = 0; i < cnt; i++) tmp[i] = null;

        if (isTitle()) {
            if (!startGame && (kJump || kDash)) {
                startGameFlash = 50;
                startGame = true;
            }
            if (startGame) {
                startGameFlash--;
                if (startGameFlash <= -30) beginGame();
            }
        }
    }

    void updateObj(Obj t) {
        switch (t.type) {
            case T_PLAYER: updatePlayer(t); break;
            case T_SPAWN: updateSpawn(t); break;
            case T_SPRING: updateSpring(t); break;
            case T_BALLOON: updateBalloon(t); break;
            case T_FALL: updateFall(t); break;
            case T_SMOKE:
                t.spr += 0.2f;
                if (t.spr >= 32) destroyObject(t);
                break;
            case T_FRUIT: {
                Obj hit = collide(t, T_PLAYER, 0, 0);
                if (hit != null) {
                    hit.djump = maxDjump;
                    gotFruit[1 + levelIndex()] = true;
                    initObject(T_LIFEUP, t.x, t.y);
                    destroyObject(t);
                }
                t.offset += 1;
                t.y = t.start + sin(t.offset / 40) * 2.5f;
                break;
            }
            case T_FLYFRUIT: updateFlyFruit(t); break;
            case T_LIFEUP:
                t.duration -= 1;
                if (t.duration <= 0) destroyObject(t);
                break;
            case T_FAKEWALL: {
                t.hx = -1; t.hy = -1; t.hw = 18; t.hh = 18;
                Obj hit = collide(t, T_PLAYER, 0, 0);
                if (hit != null && hit.dashEffectTime > 0) {
                    hit.spdx = -sign(hit.spdx) * 1.5f;
                    hit.spdy = -1.5f;
                    hit.dashTime = -1;
                    destroyObject(t);
                    initObject(T_SMOKE, t.x, t.y);
                    initObject(T_SMOKE, t.x + 8, t.y);
                    initObject(T_SMOKE, t.x, t.y + 8);
                    initObject(T_SMOKE, t.x + 8, t.y + 8);
                    initObject(T_FRUIT, t.x + 4, t.y + 4);
                }
                t.hx = 0; t.hy = 0; t.hw = 16; t.hh = 16;
                break;
            }
            case T_KEY: {
                float was = flr(t.spr);
                t.spr = 9 + (sin(frames / 30f) + 0.5f);
                float is = flr(t.spr);
                if (is == 10 && is != was) t.flipx = !t.flipx;
                if (check(t, T_PLAYER, 0, 0)) {
                    destroyObject(t);
                    hasKey = true;
                }
                break;
            }
            case T_CHEST:
                if (hasKey) {
                    t.timer--;
                    t.x = t.start - 1 + rnd(3);
                    if (t.timer <= 0) {
                        initObject(T_FRUIT, t.x, t.y - 4);
                        destroyObject(t);
                    }
                }
                break;
            case T_PLATFORM: updatePlatform(t); break;
            default:
        }
    }

    void updatePlayer(Obj t) {
        if (pausePlayer) return;

        int input = kR ? 1 : (kL ? -1 : 0);

        if (spikesAt(t.x + t.hx, t.y + t.hy, t.hw, t.hh, t.spdx, t.spdy)) killPlayer(t);
        if (t.y > 128) killPlayer(t);

        boolean onGround = isSolid(t, 0, 1);
        boolean onIce = isIce(t, 0, 1);

        if (onGround && !t.wasOnGround) initObject(T_SMOKE, t.x, t.y + 4);

        boolean jump = kJump && !t.pJump;
        t.pJump = kJump;
        if (jump) t.jbuffer = 4;
        else if (t.jbuffer > 0) t.jbuffer--;

        boolean dash = kDash && !t.pDash;
        t.pDash = kDash;

        if (onGround) {
            t.grace = 6;
            if (t.djump < maxDjump) t.djump = maxDjump;
        } else if (t.grace > 0) {
            t.grace--;
        }

        t.dashEffectTime--;
        if (t.dashTime > 0) {
            initObject(T_SMOKE, t.x, t.y);
            t.dashTime--;
            t.spdx = appr(t.spdx, t.dashTx, t.dashAx);
            t.spdy = appr(t.spdy, t.dashTy, t.dashAy);
        } else {
            float maxrun = 1;
            float accel = 0.6f;
            float deccel = 0.15f;

            if (!onGround) {
                accel = 0.4f;
            } else if (onIce) {
                accel = 0.05f;
            }

            if (Math.abs(t.spdx) > maxrun) {
                t.spdx = appr(t.spdx, sign(t.spdx) * maxrun, deccel);
            } else {
                t.spdx = appr(t.spdx, input * maxrun, accel);
            }

            if (t.spdx != 0) t.flipx = (t.spdx < 0);

            float maxfall = 2;
            float gravity = 0.21f;
            if (Math.abs(t.spdy) <= 0.15f) gravity *= 0.5f;

            if (input != 0 && isSolid(t, input, 0) && !isIce(t, input, 0)) {
                maxfall = 0.4f;
                if (rnd(10) < 2) initObject(T_SMOKE, t.x + input * 6, t.y);
            }

            if (!onGround) t.spdy = appr(t.spdy, maxfall, gravity);

            if (t.jbuffer > 0) {
                if (t.grace > 0) {
                    t.jbuffer = 0;
                    t.grace = 0;
                    t.spdy = -2;
                    initObject(T_SMOKE, t.x, t.y + 4);
                } else {
                    int wallDir = isSolid(t, -3, 0) ? -1 : (isSolid(t, 3, 0) ? 1 : 0);
                    if (wallDir != 0) {
                        t.jbuffer = 0;
                        t.spdy = -2;
                        t.spdx = -wallDir * (maxrun + 1);
                        if (!isIce(t, wallDir * 3, 0)) initObject(T_SMOKE, t.x + wallDir * 6, t.y);
                    }
                }
            }

            float dFull = 5;
            float dHalf = dFull * 0.70710678118f;

            if (t.djump > 0 && dash) {
                initObject(T_SMOKE, t.x, t.y);
                t.djump--;
                t.dashTime = 4;
                hasDashed = true;
                t.dashEffectTime = 10;
                int vInput = kU ? -1 : (kD ? 1 : 0);
                if (input != 0) {
                    if (vInput != 0) {
                        t.spdx = input * dHalf;
                        t.spdy = vInput * dHalf;
                    } else {
                        t.spdx = input * dFull;
                        t.spdy = 0;
                    }
                } else if (vInput != 0) {
                    t.spdx = 0;
                    t.spdy = vInput * dFull;
                } else {
                    t.spdx = (t.flipx ? -1 : 1);
                    t.spdy = 0;
                }

                freeze = 2;
                shake = 6;
                t.dashTx = 2 * sign(t.spdx);
                t.dashTy = 2 * sign(t.spdy);
                t.dashAx = 1.5f;
                t.dashAy = 1.5f;

                if (t.spdy < 0) t.dashTy *= .75f;
                if (t.spdy != 0) t.dashAx *= 0.70710678118f;
                if (t.spdx != 0) t.dashAy *= 0.70710678118f;
            } else if (dash && t.djump <= 0) {
                initObject(T_SMOKE, t.x, t.y);
            }
        }

        // animation
        t.sprOff += 0.25f;
        if (!onGround) {
            if (isSolid(t, input, 0)) t.spr = 5;
            else t.spr = 3;
        } else if (kD) {
            t.spr = 6;
        } else if (kU) {
            t.spr = 7;
        } else if (t.spdx == 0 || (!kL && !kR)) {
            t.spr = 1;
        } else {
            t.spr = 1 + fmod(t.sprOff, 4);
        }

        if (t.y < -4 && levelIndex() < 30) nextRoom();

        t.wasOnGround = onGround;
    }

    void updateSpawn(Obj t) {
        if (t.state == 0) {
            if (t.y < t.targetY + 16) {
                t.state = 1;
                t.delay = 3;
            }
        } else if (t.state == 1) {
            t.spdy += 0.5f;
            if (t.spdy > 0 && t.delay > 0) {
                t.spdy = 0;
                t.delay--;
            }
            if (t.spdy > 0 && t.y > t.targetY) {
                t.y = t.targetY;
                t.spdx = 0;
                t.spdy = 0;
                t.state = 2;
                t.delay = 5;
                shake = 5;
                initObject(T_SMOKE, t.x, t.y + 4);
            }
        } else if (t.state == 2) {
            t.delay--;
            t.spr = 6;
            if (t.delay < 0) {
                destroyObject(t);
                initObject(T_PLAYER, t.x, t.y);
            }
        }
    }

    void updateSpring(Obj t) {
        if (t.hideFor > 0) {
            t.hideFor--;
            if (t.hideFor <= 0) {
                t.spr = 18;
                t.delay = 0;
            }
        } else if (t.spr == 18) {
            Obj hit = collide(t, T_PLAYER, 0, 0);
            if (hit != null && hit.spdy >= 0) {
                t.spr = 19;
                hit.y = t.y - 4;
                hit.spdx *= 0.2f;
                hit.spdy = -3;
                hit.djump = maxDjump;
                t.delay = 10;
                initObject(T_SMOKE, t.x, t.y);
                Obj below = collide(t, T_FALL, 0, 1);
                if (below != null) breakFallFloor(below);
            }
        } else if (t.delay > 0) {
            t.delay--;
            if (t.delay <= 0) t.spr = 18;
        }
        if (t.hideIn > 0) {
            t.hideIn--;
            if (t.hideIn <= 0) {
                t.hideFor = 60;
                t.spr = 0;
            }
        }
    }

    void updateBalloon(Obj t) {
        if (t.spr == 22) {
            t.offset += 0.01f;
            t.y = t.start + sin(t.offset) * 2;
            Obj hit = collide(t, T_PLAYER, 0, 0);
            if (hit != null && hit.djump < maxDjump) {
                initObject(T_SMOKE, t.x, t.y);
                hit.djump = maxDjump;
                t.spr = 0;
                t.timer = 60;
            }
        } else if (t.timer > 0) {
            t.timer--;
        } else {
            initObject(T_SMOKE, t.x, t.y);
            t.spr = 22;
        }
    }

    void updateFall(Obj t) {
        if (t.state == 0) {
            if (check(t, T_PLAYER, 0, -1) || check(t, T_PLAYER, -1, 0) || check(t, T_PLAYER, 1, 0)) {
                breakFallFloor(t);
            }
        } else if (t.state == 1) {
            t.delay--;
            if (t.delay <= 0) {
                t.state = 2;
                t.delay = 60;
                t.collideable = false;
            }
        } else if (t.state == 2) {
            t.delay--;
            if (t.delay <= 0 && !check(t, T_PLAYER, 0, 0)) {
                t.state = 0;
                t.collideable = true;
                initObject(T_SMOKE, t.x, t.y);
            }
        }
    }

    void breakFallFloor(Obj o) {
        if (o.state == 0) {
            o.state = 1;
            o.delay = 15;
            initObject(T_SMOKE, o.x, o.y);
            Obj hit = collide(o, T_SPRING, 0, -1);
            if (hit != null) hit.hideIn = 15;
        }
    }

    void updateFlyFruit(Obj t) {
        if (t.fly) {
            t.spdy = appr(t.spdy, -3.5f, 0.25f);
            if (t.y < -16) destroyObject(t);
        } else {
            if (hasDashed) t.fly = true;
            t.step += 0.05f;
            t.spdy = sin(t.step) * 0.5f;
        }
        Obj hit = collide(t, T_PLAYER, 0, 0);
        if (hit != null) {
            hit.djump = maxDjump;
            gotFruit[1 + levelIndex()] = true;
            initObject(T_LIFEUP, t.x, t.y);
            destroyObject(t);
        }
    }

    void updatePlatform(Obj t) {
        t.spdx = t.dir * 0.65f;
        if (t.x < -16) t.x = 128;
        else if (t.x > 128) t.x = -16;
        if (!check(t, T_PLAYER, 0, 0)) {
            Obj hit = collide(t, T_PLAYER, 0, -1);
            if (hit != null) moveX(hit, ifl(t.x - t.last), 1);
        }
        t.last = t.x;
    }

    // ================= drawing primitives =================
    static int X(float v) { return (int) Math.floor(v * 1.875f); }

    int col(int c) { return PAL[palMap[c & 15]] & 0xFFFFFF; }

    void rectfill(float x0, float y0, float x1, float y1, int c) {
        int a = ifl(x0), b = ifl(y0), cc = ifl(x1), d = ifl(y1);
        if (a > cc) { int t = a; a = cc; cc = t; }
        if (b > d) { int t = b; b = d; d = t; }
        int px = X(a), py = X(b), w = X(cc + 1) - px, h = X(d + 1) - py;
        if (w <= 0 || h <= 0) return;
        g.setColor(col(c));
        g.fillRect(px, py, w, h);
    }

    void vline(float x, float y0, float y1, int c) {
        int ix = ifl(x);
        int a = ifl(Math.min(y0, y1)), b = ifl(Math.max(y0, y1));
        int px = X(ix), py = X(a), w = X(ix + 1) - px, h = X(b + 1) - py;
        if (w <= 0 || h <= 0) return;
        g.setColor(col(c));
        g.fillRect(px, py, w, h);
    }

    void circfill(float cx, float cy, int r, int c) {
        int ix = ifl(cx), iy = ifl(cy);
        int x0 = X(ix - r), y0 = X(iy - r);
        int w = X(ix + r + 1) - x0, h = X(iy + r + 1) - y0;
        g.setColor(col(c));
        g.fillArc(x0, y0, w, h, 0, 360);
    }

    void print(String s, float x, float y, int c) {
        g.setColor(col(c));
        g.setFont(font);
        for (int i = 0; i < s.length(); i++) {
            g.drawChar(s.charAt(i), X(x + 4 * i), X(y) - 1, Graphics.TOP | Graphics.LEFT);
        }
    }

    void spr(float nn, float x, float y, boolean fx, boolean fy) {
        sprFrom(sheetCur, nn, x, y, fx, fy);
    }

    void sprFrom(Image img, float nn, float x, float y, boolean fx, boolean fy) {
        int id = ifl(nn);
        if (id <= 0 || id > 127) return;
        int tr;
        if (fx) tr = fy ? Sprite.TRANS_ROT180 : Sprite.TRANS_MIRROR;
        else tr = fy ? Sprite.TRANS_MIRROR_ROT180 : Sprite.TRANS_NONE;
        g.drawRegion(img, (id & 15) * 15, (id >> 4) * 15, 15, 15, tr, X(x), X(y),
                Graphics.TOP | Graphics.LEFT);
    }

    void drawMap(int L, int offX) {
        int c = lc[L];
        int[] ax = lx[L], ay = ly[L], ai = lid[L];
        for (int i = 0; i < c; i++) {
            int id = ai[i];
            g.drawRegion(sheetCur, (id & 15) * 15, (id >> 4) * 15, 15, 15, Sprite.TRANS_NONE,
                    X(ax[i] + offX), X(ay[i]), Graphics.TOP | Graphics.LEFT);
        }
    }

    // ================= drawing =================
    int hairColor(int djump) {
        if (djump == 1) return 8;
        if (djump == 2) return 7 + ifl(fmod(frames / 3f, 2)) * 4;
        return 12;
    }

    void drawHair(Obj o, int facing, int hc) {
        float lastX = o.x + 4 - facing * 2;
        float lastY = o.y + (kD ? 4 : 3);
        for (int i = 0; i < 5; i++) {
            o.hairX[i] += (lastX - o.hairX[i]) / 1.5f;
            o.hairY[i] += (lastY + 0.5f - o.hairY[i]) / 1.5f;
            circfill(o.hairX[i], o.hairY[i], o.hairS[i], hc);
            lastX = o.hairX[i];
            lastY = o.hairY[i];
        }
    }

    void sprPlayer(Obj o, int hc) {
        Image img = sheetCur;
        if (hc == 12) img = hair12;
        else if (hc == 7) img = hair7;
        else if (hc == 11) img = hair11;
        sprFrom(img, o.spr, o.x, o.y, o.flipx, o.flipy);
    }

    void draw() {
        if (freeze > 0) return;
        g = getGraphics();

        // palette (title start flash)
        for (int i = 0; i < 16; i++) palMap[i] = i;
        int c = 10;
        if (startGame) {
            if (startGameFlash > 10) {
                if (frames % 10 < 5) c = 7;
            } else if (startGameFlash > 5) {
                c = 2;
            } else if (startGameFlash > 0) {
                c = 1;
            } else {
                c = 0;
            }
            if (c < 10) {
                palMap[6] = c; palMap[12] = c; palMap[13] = c;
                palMap[5] = c; palMap[1] = c; palMap[7] = c;
                if (flashSig != c) {
                    flashSheet = buildImage(16, 8, palMap);
                    flashSig = c;
                }
                sheetCur = flashSheet;
            } else {
                sheetCur = sheet;
            }
        } else {
            sheetCur = sheet;
            if (flashSheet != null) {
                flashSheet = null;
                flashSig = -1;
            }
        }

        // black everywhere, then game area
        g.setClip(0, 0, getWidth(), getHeight());
        g.setColor(0);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.translate(-g.getTranslateX(), -g.getTranslateY());
        g.setClip(ox, oy, 240, 240);
        g.translate(ox + camX, oy + camY);

        int bgCol = 0;
        if (flashBg) bgCol = frames / 5;
        else if (newBg) bgCol = 2;
        rectfill(0, 0, 128, 128, bgCol);

        // clouds
        if (!isTitle()) {
            for (int i = 0; i <= 16; i++) {
                cX[i] += cSpd[i];
                rectfill(cX[i], cY[i], cX[i] + cW[i], cY[i] + 4 + (1 - cW[i] / 64) * 12, newBg ? 14 : 1);
                if (cX[i] > 128) {
                    cX[i] = -cW[i];
                    cY[i] = rnd(128 - 8);
                }
            }
        }

        // bg terrain
        drawMap(0, 0);

        // snapshot for drawing
        int cnt = n;
        for (int i = 0; i < cnt; i++) tmp[i] = objs[i];

        for (int i = 0; i < cnt; i++) {
            Obj o = tmp[i];
            if (o != null && !o.dead && (o.type == T_PLATFORM || o.type == T_BIGCHEST)) drawObject(o);
        }

        drawMap(1, isTitle() ? -4 : 0);

        for (int i = 0; i < cnt; i++) {
            Obj o = tmp[i];
            if (o != null && !o.dead && o.type != T_PLATFORM && o.type != T_BIGCHEST) drawObject(o);
        }
        for (int i = 0; i < cnt; i++) tmp[i] = null;

        // fg terrain
        drawMap(2, 0);

        // particles
        for (int i = 0; i <= 24; i++) {
            pX[i] += pSpd[i];
            pY[i] += sin(pOff[i]);
            pOff[i] += Math.min(0.05f, pSpd[i] / 32);
            rectfill(pX[i], pY[i], pX[i] + pS[i], pY[i] + pS[i], pC[i]);
            if (pX[i] > 128 + 4) {
                pX[i] = -4;
                pY[i] = rnd(128);
            }
        }

        // dead particles
        for (int i = 0; i < 8; i++) {
            if (dT[i] > 0) {
                dX[i] += dSx[i];
                dY[i] += dSy[i];
                dT[i]--;
                float r = dT[i] / 5f;
                rectfill(dX[i] - r, dY[i] - r, dX[i] + r, dY[i] + r, 14 + dT[i] % 2);
            }
        }

        // credits
        if (isTitle()) {
            print("X+C", 58, 80, 5);
            print("MATT THORSON", 42, 96, 5);
            print("NOEL BERRY", 46, 102, 5);
        }

        if (levelIndex() == 30) {
            Obj p = null;
            for (int i = 0; i < n; i++) {
                if (objs[i].type == T_PLAYER) {
                    p = objs[i];
                    break;
                }
            }
            if (p != null) {
                float diff = Math.min(24, 40 - Math.abs(p.x + 4 - 64));
                rectfill(0, 0, diff, 128, 0);
                rectfill(128 - diff, 0, 128, 128, 0);
            }
        }

        flushGraphics();
    }

    String pad2(int v) { return v < 10 ? "0" + v : "" + v; }

    void drawTime(float x, float y) {
        int h = minutes / 60;
        int m = minutes % 60;
        rectfill(x, y, x + 32, y + 6, 0);
        print(pad2(h) + ":" + pad2(m) + ":" + pad2(seconds), x + 1, y + 1, 7);
    }

    void drawObject(Obj t) {
        switch (t.type) {
            case T_PLAYER: {
                if (t.x < -1 || t.x > 121) {
                    t.x = clamp(t.x, -1, 121);
                    t.spdx = 0;
                }
                int hc = hairColor(t.djump);
                drawHair(t, t.flipx ? -1 : 1, hc);
                sprPlayer(t, hc);
                break;
            }
            case T_SPAWN: {
                int hc = hairColor(maxDjump);
                drawHair(t, 1, hc);
                sprPlayer(t, hc);
                break;
            }
            case T_BALLOON:
                if (t.spr == 22) {
                    spr(13 + fmod(t.offset * 8, 3), t.x, t.y + 6, false, false);
                    spr(t.spr, t.x, t.y, false, false);
                }
                break;
            case T_FALL:
                if (t.state != 2) {
                    if (t.state != 1) spr(23, t.x, t.y, false, false);
                    else spr(23 + (15 - t.delay) / 5, t.x, t.y, false, false);
                }
                break;
            case T_FLYFRUIT: {
                float off = 0;
                if (!t.fly) {
                    float dir = sin(t.step);
                    if (dir < 0) off = 1 + Math.max(0, sign(t.y - t.start));
                } else {
                    off = fmod(off + 0.25f, 3);
                }
                spr(45 + off, t.x - 6, t.y - 2, true, false);
                spr(t.spr, t.x, t.y, false, false);
                spr(45 + off, t.x + 6, t.y - 2, false, false);
                break;
            }
            case T_LIFEUP:
                t.flash += 0.5f;
                print("1000", t.x - 2, t.y, 7 + ifl(fmod(t.flash, 2)));
                break;
            case T_FAKEWALL:
                spr(64, t.x, t.y, false, false);
                spr(65, t.x + 8, t.y, false, false);
                spr(80, t.x, t.y + 8, false, false);
                spr(81, t.x + 8, t.y + 8, false, false);
                break;
            case T_PLATFORM:
                spr(11, t.x, t.y - 1, false, false);
                spr(12, t.x + 8, t.y - 1, false, false);
                break;
            case T_MESSAGE:
                drawMessage(t);
                break;
            case T_BIGCHEST:
                drawBigChest(t);
                break;
            case T_ORB: {
                t.spdy = appr(t.spdy, 0, 0.5f);
                Obj hit = collide(t, T_PLAYER, 0, 0);
                if (t.spdy == 0 && hit != null) {
                    freeze = 10;
                    shake = 10;
                    destroyObject(t);
                    maxDjump = 2;
                    hit.djump = 2;
                }
                spr(102, t.x, t.y, false, false);
                float off = frames / 30f;
                for (int i = 0; i < 8; i++) {
                    circfill(t.x + 4 + cos(off + i / 8f) * 8, t.y + 4 + sin(off + i / 8f) * 8, 1, 7);
                }
                break;
            }
            case T_FLAG:
                t.spr = 118 + fmod(frames / 5f, 3);
                spr(t.spr, t.x, t.y, false, false);
                if (t.show) {
                    rectfill(32, 2, 96, 31, 0);
                    spr(26, 55, 6, false, false);
                    print("X" + t.score, 64, 9, 7);
                    drawTime(49, 16);
                    print("DEATHS:" + deaths, 48, 24, 7);
                } else if (check(t, T_PLAYER, 0, 0)) {
                    t.show = true;
                }
                break;
            case T_ROOMTITLE:
                t.delay--;
                if (t.delay < -30) {
                    destroyObject(t);
                } else if (t.delay < 0) {
                    rectfill(24, 58, 104, 70, 0);
                    if (roomX == 3 && roomY == 1) {
                        print("OLD SITE", 48, 62, 7);
                    } else if (levelIndex() == 30) {
                        print("SUMMIT", 52, 62, 7);
                    } else {
                        int level = (1 + levelIndex()) * 100;
                        print(level + " M", 52 + (level < 1000 ? 2 : 0), 62, 7);
                    }
                    drawTime(4, 4);
                }
                break;
            default:
                // generic sprite (spring, smoke, fruit, key, chest)
                if (t.spr > 0) spr(t.spr, t.x, t.y, t.flipx, t.flipy);
        }
    }

    void drawMessage(Obj t) {
        if (check(t, T_PLAYER, 4, 0)) {
            if (t.offset < MSG.length()) t.offset += 0.5f;
            int upto = ifl(t.offset);
            float offx = 8, offy = 96;
            for (int i = 0; i < upto && i < MSG.length(); i++) {
                char ch = MSG.charAt(i);
                if (ch != '#') {
                    rectfill(offx - 2, offy - 2, offx + 7, offy + 6, 7);
                    print(String.valueOf(ch), offx, offy, 0);
                    offx += 5;
                } else {
                    offx = 8;
                    offy += 7;
                }
            }
        } else {
            t.offset = 0;
        }
    }

    void drawBigChest(Obj t) {
        if (t.state == 0) {
            Obj hit = collide(t, T_PLAYER, 0, 8);
            if (hit != null && isSolid(hit, 0, 1)) {
                pausePlayer = true;
                hit.spdx = 0;
                hit.spdy = 0;
                t.state = 1;
                initObject(T_SMOKE, t.x, t.y);
                initObject(T_SMOKE, t.x + 8, t.y);
                t.timer = 60;
                t.pX = new float[50];
                t.pY = new float[50];
                t.pH = new float[50];
                t.pSpd = new float[50];
                t.pCount = 0;
            }
            spr(96, t.x, t.y, false, false);
            spr(97, t.x + 8, t.y, false, false);
        } else if (t.state == 1) {
            t.timer--;
            shake = 5;
            flashBg = true;
            if (t.timer <= 45 && t.pCount < 50) {
                int k = t.pCount++;
                t.pX[k] = 1 + rnd(14);
                t.pY[k] = 0;
                t.pH[k] = 32 + rnd(32);
                t.pSpd[k] = 8 + rnd(8);
            }
            if (t.timer < 0) {
                t.state = 2;
                t.pCount = 0;
                flashBg = false;
                newBg = true;
                initObject(T_ORB, t.x + 4, t.y + 4);
                pausePlayer = false;
            }
            for (int i = 0; i < t.pCount; i++) {
                t.pY[i] += t.pSpd[i];
                vline(t.x + t.pX[i], t.y + 8 - t.pY[i],
                        Math.min(t.y + 8 - t.pY[i] + t.pH[i], t.y + 8), 7);
            }
        }
        spr(112, t.x, t.y + 8, false, false);
        spr(113, t.x + 8, t.y + 8, false, false);
    }
}
