package org.ttrmobile;

/**
 * Pixel geometry shared by the touch view and host layout checks. Bounds are {x, y, width, height}.
 *
 * Portrait (controls below the game): Tap/Cursor switch at the top; joystick on the left, Throw and Jump side by
 * side like a Game Boy's B and A on the right; then Map, Gags, Tasks, Camera in a row just above Keyboard at the
 * bottom centre. Landscape (controls over the game): Tap/Cursor switch at the top centre; joystick bottom left;
 * Throw and Jump bottom right; Map, Gags, Tasks, Camera in a row just above Keyboard at the bottom centre. Windows adds a Groups button (ToonHQ groups) beside the switch in both.
 * Landscape also has a small round Controls button right of Keyboard that tucks the game-play controls away (used with a controller).
 * Corporate Clash (sprint = true) has no Map, and Gags, Tasks, Camera form a row of three. In portrait Jump is larger and
 * nearly level with Throw, Throw is level with the joystick, and Sprint (Throw's size) sits just under Jump's lower edge in
 * the gap between the two. Landscape has the same arrangement anchored bottom right, with Sprint at the bottom edge.
 */
public final class TtrControlLayout {
    private TtrControlLayout() { }
    /** Height of the game view: portrait is 5:4 across the full width, landscape is full height. */
    public static int gameHeight(int width, int height) {
        return height > width ? Math.min((int) Math.round(width * .8), (int) (height * .6)) : height;
    }
    /** Movement area: a touch anywhere here steers the joystick, whose base stays where it is. */
    public static int[] moveZone(int width, int height, float density) { return moveZone(width, height, density, false); }
    public static int[] moveZone(int width, int height, float density, boolean sprint) {
        double u = density;
        if (height > width) {
            int[] toggle = bounds("Pointer", width, height, density, sprint), row = bounds("Gags", width, height, density, sprint), toss = bounds("Throw", width, height, density, sprint);
            int top = toggle[1] + toggle[3] + (int) Math.round(6 * u), bottom = row[1] - (int) Math.round(6 * u);
            return new int[]{0, top, (int) Math.min(width / 2.0, toss[0] - 4 * u), Math.max(1, bottom - top)};
        }
        int[] keys = bounds("Keyboard", width, height, density, sprint), map = bounds(sprint ? "Gags" : "Map", width, height, density, sprint);
        int top = Math.round(height * .28f), right = (int) Math.min(width * .42, Math.min(keys[0], map[0]) - 8 * u);
        return new int[]{0, top, right, height - top};
    }
    /** Joystick {centerX, centerY, radius}. */
    public static int[] joystick(int width, int height, float density) { return joystick(width, height, density, false); }
    public static int[] joystick(int width, int height, float density, boolean sprint) {
        int[] b = bounds("Pad", width, height, density, sprint); return new int[]{b[0] + b[2] / 2, b[1] + b[3] / 2, b[2] / 2};
    }
    public static int[] bounds(String label, int width, int height, float density) { return bounds(label, width, height, density, false); }
    public static int[] bounds(String label, int width, int height, float density, boolean sprint) {
        if (label.equals("Move")) return moveZone(width, height, density, sprint);
        double u = density, w, h, x, y;
        if (height > width) {
            int top = gameHeight(width, height), space = height - top;
            double toggleW = Math.min(176 * u, width * .48), toggleH = Math.min(36 * u, space * .08);
            double toggleY = top + 20 * u + toggleH / 2;
            double row = Math.min(56 * u, Math.min(width * .15, space * .13));
            double keysW = Math.min(200 * u, width * .5), keysH = Math.min(40 * u, space * .09), keysY = height - 10 * u - keysH / 2;
            // Equal gaps from the switch down: switch, gap, Throw/Jump, gap, button row, gap, keyboard.
            double switchBottom = toggleY + toggleH / 2, keysTop = keysY - keysH / 2, free = keysTop - switchBottom - row;
            double a = sprint ? Math.min(120 * u, Math.min(width * .28, free * .355)) : Math.min(108 * u, Math.min(width * .27, free * .32));
            double b = sprint ? Math.min(80 * u, Math.min(width * .19, free * .235)) : Math.min(92 * u, Math.min(width * .23, free * .27));
            double tilt = (sprint ? .08 : .45) * (a + b) / 2, pairH = (a + b) / 2 + tilt, apart = sprint ? Math.min(20 * u, width * .04) : 6 * u;
            double s = sprint ? b : 0, under = 2 * u, groupH = sprint ? a + under + s : pairH, gap = (free - groupH) / 3;
            double padY = switchBottom + gap + groupH / 2; // the group's visual middle
            // Throw/Jump on the right half (as the joystick is on the left), level with the joystick; for Clash Throw itself is.
            double pairY = sprint ? padY - tilt / 2 : switchBottom + gap + a / 2 + tilt / 2, rowY = switchBottom + 2 * gap + groupH + row / 2;
            double pairW = a + b + apart, aX = Math.min(width * (sprint ? .72 : .75) + pairW / 2 - a / 2, width - 8 * u - a / 2), bX = aX - (a + b) / 2 - apart;
            double rowTop = rowY - row / 2, pad = Math.min(160 * u, Math.min(width * .38, 2 * Math.min(padY - switchBottom - 6 * u, rowTop - 6 * u - padY)));
            switch (label) {
                case "Pointer": w = toggleW; h = toggleH; x = width / 2.0; y = toggleY; break;
                case "Groups": w = Math.min(96 * u, width * .22); h = toggleH; x = width * .86; y = toggleY; break; // level with the switch, above Camera
                case "Map": w = h = row; x = width * .14; y = rowY; break;
                case "Gags": w = h = row; x = width * (sprint ? .26 : .38); y = rowY; break;
                case "Tasks": w = h = row; x = width * (sprint ? .5 : .62); y = rowY; break;
                case "Camera": w = h = row; x = width * (sprint ? .74 : .86); y = rowY; break;
                case "Sprint": w = h = s; x = (bX + b / 2 + aX - a / 2) / 2; y = pairY - tilt / 2 + a / 2 + under + s / 2; break; // under Jump, in the gap
                case "Pad": w = h = pad; x = width * .25; y = padY; break;
                case "Jump": w = h = a; x = aX; y = pairY - tilt / 2; break;
                case "Throw": w = h = b; x = bX; y = pairY + tilt / 2; break;
                case "Controls": w = h = keysH; x = width / 2.0 + keysW / 2 + 8 * u + keysH / 2; y = keysY; break; // landscape only; hidden here
                default: w = keysW; h = keysH; x = width / 2.0; y = keysY; // Keyboard
            }
        } else {
            // Throw and Jump keep the size they had when the row sat above them (that group was half the view height).
            // Clash: a larger Jump nearly level with Throw, Sprint (Throw's size) under Jump in the gap between the two.
            double k = height * .5 / 1.974, a = sprint ? .93 * k : k, b = sprint ? .62 * k : .84 * k, tilt = (sprint ? .08 : .45) * (a + b) / 2;
            double right = Math.max(18 * u, width * .035);
            double s = sprint ? b : 0, under = sprint ? 2 * u : 0, apart = sprint ? Math.min(20 * u, width * .02) : 6 * u;
            double aY = sprint ? height - 14 * u - s - under - a / 2 : height - 14 * u - b / 2 - tilt, bY = aY + tilt;
            double aX = width - right - a / 2, bX = aX - (a + b) / 2 - apart;
            double toggleW = Math.min(150 * u, width * .16), toggleH = Math.min(34 * u, height * .10);
            // Map, Gags, Tasks, Camera centred just above Keyboard, narrowed if needed to stay clear of the joystick and Throw.
            double keysH = Math.min(36 * u, height * .09), keysTop = height - 8 * u - keysH;
            double padRight = 18 * u + Math.min(164 * u, height * .44), throwLeft = bX - b / 2;
            double room = 2 * Math.min(width / 2.0 - padRight, throwLeft - width / 2.0) - 24 * u;
            double row = Math.min(.52 * k, (room - 30 * u) / 4), step = row + 10 * u, rowX = width / 2.0, rowY = keysTop - 8 * u - row / 2;
            switch (label) {
                case "Pad": w = h = Math.min(164 * u, height * .44); x = 18 * u + w / 2; y = height - 20 * u - h / 2; break;
                case "Jump": w = h = a; x = aX; y = aY; break;
                case "Throw": w = h = b; x = bX; y = bY; break;
                case "Map": w = h = row; x = rowX - 1.5 * step; y = rowY; break;
                case "Gags": w = h = row; x = rowX - (sprint ? 1 : .5) * step; y = rowY; break;
                case "Tasks": w = h = row; x = rowX + (sprint ? 0 : .5) * step; y = rowY; break;
                case "Camera": w = h = row; x = rowX + (sprint ? 1 : 1.5) * step; y = rowY; break;
                case "Sprint": w = h = s; x = (bX + b / 2 + aX - a / 2) / 2; y = height - 14 * u - s / 2; break;
                case "Pointer": w = toggleW; h = toggleH; x = width / 2.0; y = 10 * u + toggleH / 2; break; // top centre
                case "Groups": w = Math.min(100 * u, width * .11); h = toggleH; x = width / 2.0 + toggleW / 2 + 10 * u + w / 2; y = 10 * u + toggleH / 2; break; // right of the switch
                case "Controls": w = h = keysH; x = width / 2.0 + Math.min(180 * u, width * .2) / 2 + 8 * u + keysH / 2; y = height - 8 * u - keysH / 2; break; // right of Keyboard
                default: w = Math.min(180 * u, width * .2); h = keysH; x = width / 2.0; y = height - 8 * u - h / 2; // Keyboard
            }
        }
        int bw = Math.max(1, (int) Math.round(w)), bh = Math.max(1, (int) Math.round(h));
        return new int[]{Math.max(0, Math.min(width - bw, (int) Math.round(x - bw / 2.0))), Math.max(0, Math.min(height - bh, (int) Math.round(y - bh / 2.0))), bw, bh};
    }
}
