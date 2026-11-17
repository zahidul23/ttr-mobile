package org.ttrmobile;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.app.Activity;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.*;

/**
 * Launcher screen. The main page is "who's playing" plus one Play button and a single status card that shows only
 * what matters right now (first-time setup, a game download, the launch, or ready). Accounts and settings each
 * have their own page. Downloads and setup run in the background regardless of the page shown.
 */
public final class TtrLoginScreen implements TtrLauncher.Listener {
    private final Activity activity;
    public View view;
    private final boolean prewarm;
    private final TtrAccounts vault;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText username, password;
    private LinearLayout card, mainPanel, gamePanel, accountPanel, filesCard, readyCard, packsPanel, packList;
    private int packsLoad;
    private TextView packsIntro;
    private View gear;
    private View backdropRoot, backdropArt, backdropShade;
    private LinearLayout header, outerBox, storageBox;
    private int storageLoad;
    /** Storage used by Windows, the TTR game files and content packs (measured off the main thread when Settings opens). */
    private void refreshStorage() {
        final int load = ++storageLoad;
        if (storageBox.getChildCount() == 0) { TextView wait = text("Measuring storage\u2026", 13, MUTED); storageBox.addView(wait); }
        new Thread(() -> { long[] used = TtrUsage.measure(activity); ui.post(() -> { if (load == storageLoad) showStorage(used); }); }, "ttr-usage").start();
    }
    private void showStorage(long[] used) {
        storageBox.removeAllViews();
        TextView title = text("Storage used on this phone", 12, GOLD); title.setTypeface(null, Typeface.BOLD); title.setAllCaps(true); title.setLetterSpacing(0.08f); storageBox.addView(title);
        add(storageBox, storageRow("Windows", used[0], false), 8);
        add(storageBox, storageRow("TTR game files", used[1], false), 4);
        add(storageBox, storageRow("Content packs", used[2], false), 4);
        View line = new View(activity); line.setBackgroundColor(0x26ffffff); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))); lp.topMargin = dp(8); storageBox.addView(line, lp);
        add(storageBox, storageRow("Total", used[0] + used[1] + used[2], true), 8);
    }
    private View storageRow(String label, long bytes, boolean bold) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 14, bold ? Color.WHITE : SOFT), value = text(TtrPacks.readable(bytes), 14, bold ? Color.WHITE : SOFT); value.setGravity(Gravity.END);
        if (bold) { name.setTypeface(null, Typeface.BOLD); value.setTypeface(null, Typeface.BOLD); }
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(value);
        return row;
    }
    /** Last system-bar and cutout insets; the launcher screen pads for them, the manager over the game does not (the game area is already below them). */
    private int[] insetPad = {0, 0, 0, 0};
    /** The launcher's artwork, tint and game title; over the running game they are off, so the game shows around a card that starts at the page heading. */
    private void backdrop(boolean on) {
        backdropRoot.setBackgroundColor(on ? 0xff25485a : 0);
        header.setVisibility(on ? View.VISIBLE : View.GONE);
        backdropArt.setVisibility(on ? View.VISIBLE : View.GONE); backdropShade.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) outerBox.setPadding(dp(16) + insetPad[0], dp(20) + insetPad[1], dp(16) + insetPad[2], dp(20) + insetPad[3]);
        else outerBox.setPadding(dp(16), dp(20), dp(16), dp(20));
        ((LinearLayout.LayoutParams) packsPanel.getLayoutParams()).topMargin = dp(on ? 18 : 0); packsPanel.requestLayout();
        card.setBackground(background(on ? 0xef102b3a : 0xfc102b3a, 22)); // nearly solid over the game, so the text stays readable
    }
    private TextView authStage, fileStage, release, readyRelease, accountStatus, passwordHelp, accountTitle, accountHint;
    private ProgressPanel runtimePanel, clientPanel;
    private LinearLayout accountChoices;
    private HorizontalScrollView accountsScroll;
    private Button addAccount, changePassword;
    /** The selected account's card shows a pencil that opens its page (long-press does too). */
    private final Map<String,View> editIcons=new HashMap<>();
    private final Map<String,Button> accountButtons=new LinkedHashMap<>();
    private Map<String,Long> verifiedAccounts=new LinkedHashMap<>();
    private Button play, accountPlay, save, verify, forget, retry, cancelAuth, cancelFiles;
    private ProgressBar fileProgress, authProgress;
    /** Brief status notices: a pill floating near the bottom of the screen that fades after a few seconds. */
    private TextView notice;
    private int shownMessage = TtrLauncher.messageId;
    private String shownError;
    private static final int MAIN = 0, ACCOUNT = 1, SETTINGS = 2, PACKS = 3;
    private static final int GOLD = 0xffffd65e, INK = 0xff0a2634, MUTED = 0xffb8d0dd, SOFT = 0xffdcebf1, CHIP = 0xff254e66;

    /** Heading + step, current status, determinate bar, then detail and time remaining. */
    private final class ProgressPanel {
        final LinearLayout root = new LinearLayout(activity);
        final TextView heading, step, status, detail, eta;
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        ProgressPanel(String title) {
            root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(12), dp(16), dp(14)); root.setBackground(background(0x26ffffff, 16));
            LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL);
            heading = text(title, 12, GOLD); heading.setTypeface(null, Typeface.BOLD); heading.setAllCaps(true); heading.setLetterSpacing(0.08f);
            top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            step = text("", 12, MUTED); top.addView(step);
            root.addView(top);
            status = text("", 16, Color.WHITE); status.setTypeface(null, Typeface.BOLD); add(root, status, 6);
            bar.setMax(100); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff)); add(root, bar, 8);
            LinearLayout bottom = new LinearLayout(activity);
            detail = text("", 12, SOFT); bottom.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
            eta = text("", 12, 0xffffe8a3); eta.setGravity(Gravity.END); bottom.addView(eta);
            add(root, bottom, 4);
        }
        private String logged = "";
        void show(TtrStartupProgress.Stage s) {
            String summary = heading.getText() + ": " + s.label + " | " + s.detail + " | " + s.percent + "% | " + s.eta;
            if (!summary.equals(logged)) { logged = summary; android.util.Log.i("TtrProgress", summary); }
            status.setText(s.label);
            step.setText(s.steps > 0 && s.percent < 100 ? "Step " + s.step + " of " + s.steps : "");
            boolean done = s.percent >= 100;
            bar.setVisibility(done ? View.GONE : View.VISIBLE);
            bar.setIndeterminate(s.percent < 0); if (s.percent >= 0) bar.setProgress(s.percent);
            detail.setText(done ? "" : s.detail); detail.setVisibility(done || s.detail.isEmpty() ? View.GONE : View.VISIBLE);
            String right = done ? "" : s.percent >= 0 ? s.percent + "% · " + s.eta : s.eta;
            eta.setText(right); eta.setVisibility(right.isEmpty() ? View.GONE : View.VISIBLE);
            ((View) eta.getParent()).setVisibility(done || (s.detail.isEmpty() && right.isEmpty()) ? View.GONE : View.VISIBLE);
        }
    }
    private android.app.Dialog codeDialog;
    private List<String> names = new ArrayList<>();
    private int shownChallenge = -1, panel = MAIN;
    private boolean resumed, runtimeStarting, waitingStorage, previousBusy, editingPassword;
    /** Play was tapped while game files were updating: start as soon as they are ready (tap again to cancel). */
    private boolean playQueued;
    private static String draftUsername = "", draftPassword = "";
    private static boolean draftNew;
    private final Runnable watch = new Runnable() {
        public void run() {
            if (!resumed) return;
            if (TtrRuntime.lastError != null) runtimeStarting = false;
            if (prewarm && TtrPlatform.consumeRuntimeRetry()) warmWindows();
            if (runtimeStarting && (TtrRuntime.clientVisible(activity) || TtrRuntime.phase(activity).endsWith("failed") || TtrRuntime.phase(activity).equals("window_timeout") || TtrRuntime.phase(activity).equals("session_invalid"))) runtimeStarting = false;
            changed();
            ui.postDelayed(this, 1000);
        }
    };
    public TtrLoginScreen(Activity activity, boolean prewarm) {
        this(activity,prewarm,new TtrAccounts(activity));
    }
    TtrLoginScreen(Activity activity,boolean prewarm,TtrAccounts vault) {
        this.activity = activity; this.prewarm = prewarm;this.vault=vault;
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildScreen(); refreshAccounts();
        if(TtrLauncher.selected.isEmpty()){username.setText(draftUsername);password.setText(draftPassword);}
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String label, int size, int color) { TextView t = new TextView(activity); t.setText(label); t.setTextSize(size); t.setTextColor(color); return t; }
    private TextView heading(String label) { TextView t = text(label, 15, Color.WHITE); t.setTypeface(null, Typeface.BOLD); return t; }
    private void add(LinearLayout parent, View view, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(view, p); }
    private Button button(String label) { Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CHIP)); tactile(b); return b; }
    /** A short tick on press. */
    private static void tactile(View v) { v.setOnTouchListener((view, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && view.isEnabled() && TtrTouchControls.haptics(view.getContext())) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); return false; }); }
    /** A quieter text-only button for secondary actions. */
    private Button link(String label) { Button b = button(label); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000)); b.setTextColor(MUTED); b.setStateListAnimator(null); return b; }
    private Drawable artwork(String name) { try (InputStream in = activity.getAssets().open("ttr/art/" + name)) { return Drawable.createFromStream(in, name); } catch (Exception e) { return null; } }
    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(activity); e.setHint(hint); e.setTextColor(Color.WHITE); e.setHintTextColor(0xffb4c9d4); e.setSingleLine(true); e.setTextSize(16);
        e.setPadding(dp(14), dp(12), dp(14), dp(12)); e.setBackground(background(0xff173344, 10)); e.setSaveEnabled(false);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); return e;
    }
    /** Settings icon: a gear drawn to scale (fonts render the gear character inconsistently). */
    private final class Gear extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Gear() { super(activity); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * .48f, tooth = r * .34f;
            paint.setColor(SOFT); paint.setStyle(android.graphics.Paint.Style.FILL);
            for (int i = 0; i < 8; i++) { c.save(); c.rotate(i * 45, cx, cy); c.drawRoundRect(cx - tooth / 2, cy - r - tooth * .8f, cx + tooth / 2, cy - r + tooth, tooth * .25f, tooth * .25f, paint); c.restore(); }
            c.drawCircle(cx, cy, r, paint);
            paint.setColor(0xff102b3a); c.drawCircle(cx, cy, r * .42f, paint);
        }
    }
    /** The end of a page: a divider, then a solid Back button. */
    private void backFooter(LinearLayout page, Runnable back) {
        View divider = new View(activity); divider.setBackgroundColor(0x26ffffff);
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))); dp1.topMargin = dp(22); page.addView(divider, dp1);
        Button done = button("Back"); done.setTypeface(null, Typeface.BOLD); done.setTextSize(16); done.setOnClickListener(v -> back.run());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(54)); bp.topMargin = dp(16); page.addView(done, bp);
    }
    /** Back from the account page: to the main page (choosing again if a new account was being added). */
    private void leaveAccountPage() { editingPassword = false; draftNew = false; if (TtrLauncher.selected.isEmpty() && !names.isEmpty()) refreshAccounts(); showPanel(MAIN); }
    /** Edit icon: a pencil drawn to scale, like the gear (the pencil character renders thin on some phones). */
    private final class Pencil extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path tip = new android.graphics.Path();
        Pencil() { super(activity); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), len = s * .52f, thick = s * .16f;
            c.save(); c.rotate(-45, w / 2, h / 2); paint.setColor(INK); paint.setStyle(android.graphics.Paint.Style.FILL);
            float left = w / 2 - len / 2, top = h / 2 - thick / 2;
            c.drawRoundRect(left + thick * .9f, top, left + len, top + thick, thick * .25f, thick * .25f, paint); // body
            tip.reset(); tip.moveTo(left + thick * .7f, top); tip.lineTo(left, h / 2); tip.lineTo(left + thick * .7f, top + thick); tip.close(); c.drawPath(tip, paint);
            c.restore();
        }
    }
    /** Account chips: flat rounded cards (no button shadow), so the row lines up. */
    private void chip(Button b, int color) { b.setBackgroundTintList(null); b.setBackground(background(color, 14)); b.setStateListAnimator(null); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private String appBuild() { try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xff25485a); backdropRoot = root;
        ImageView art = new ImageView(activity); backdropArt = art; art.setImageDrawable(artwork("background.png")); art.setScaleType(ImageView.ScaleType.CENTER_CROP); root.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity); backdropShade = shade; shade.setBackgroundColor(0x850b1b25); root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        LinearLayout outer = new LinearLayout(activity); outerBox = outer; outer.setGravity(Gravity.CENTER); outer.setPadding(dp(16), dp(20), dp(16), dp(20));
        card = column(); card.setPadding(dp(22), dp(20), dp(22), dp(16)); card.setBackground(background(0xef102b3a, 22));

        // Header: game icon and name, settings on the right.
        header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity); icon.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager())); icon.setContentDescription("TTR Mobile (unofficial)"); header.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = column();
        TextView title = text("Toontown Rewritten", 22, Color.WHITE); title.setTypeface(null, Typeface.BOLD); titles.addView(title);
        titles.addView(text("Android wrapper for Windows TTR", 13, MUTED));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = dp(12); header.addView(titles, titleParams);
        gear = new Gear(); gear.setContentDescription("Settings"); gear.setClickable(true); gear.setFocusable(true); tactile(gear);
        gear.setBackground(background(0x26ffffff, 22));
        gear.setOnClickListener(v -> showPanel(panel == SETTINGS ? MAIN : SETTINGS)); header.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        add(card, header, 0);

        // Main page: who's playing, Play, and one status card.
        mainPanel = column(); add(card, mainPanel, 18);
        mainPanel.addView(heading("Who's playing?"));
        accountsScroll = new HorizontalScrollView(activity); accountsScroll.setHorizontalScrollBarEnabled(false);
        accountChoices = new LinearLayout(activity); accountChoices.setBaselineAligned(false); accountsScroll.addView(accountChoices); add(mainPanel, accountsScroll, 8);
        addAccount = button("+ Add\naccount"); addAccount.setTextColor(SOFT); chip(addAccount, 0x2effffff); addAccount.setPadding(dp(18), dp(6), dp(18), dp(6)); addAccount.setGravity(Gravity.CENTER);
        addAccount.setOnClickListener(v -> newAccount());
        // A hint only while no account is chosen ("Add your account…", "Tap an account…").
        accountStatus = text("", 13, MUTED); accountStatus.setPadding(0, dp(2), 0, dp(2)); add(mainPanel, accountStatus, 6);
        play = button("Play"); play.setTextColor(INK); play.setTypeface(null, Typeface.BOLD); play.setTextSize(18);
        play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD)); play.setOnClickListener(v -> play());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, dp(62)); playParams.topMargin = dp(14); mainPanel.addView(play, playParams);
        // Status: only the one that matters now is shown.
        runtimePanel = new ProgressPanel("Getting ready"); add(mainPanel, runtimePanel.root, 14);
        clientPanel = new ProgressPanel("Launching"); add(mainPanel, clientPanel.root, 14); clientPanel.root.setVisibility(View.GONE);
        filesCard = column(); filesCard.setPadding(dp(16), dp(12), dp(16), dp(14)); filesCard.setBackground(background(0x26ffffff, 16)); add(mainPanel, filesCard, 14);
        TextView filesHeading = text("Game files", 12, GOLD); filesHeading.setTypeface(null, Typeface.BOLD); filesHeading.setAllCaps(true); filesHeading.setLetterSpacing(0.08f); filesCard.addView(filesHeading);
        fileStage = text("Preparing game files…", 15, Color.WHITE); add(filesCard, fileStage, 6);
        fileProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); fileProgress.setIndeterminate(true);
        fileProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD)); add(filesCard, fileProgress, 6);
        cancelFiles = link("Cancel download"); cancelFiles.setOnClickListener(v -> TtrGameFiles.cancel()); add(filesCard, cancelFiles, 2);
        readyCard = new LinearLayout(activity); readyCard.setGravity(Gravity.CENTER_VERTICAL); readyCard.setPadding(dp(16), dp(12), dp(16), dp(12)); readyCard.setBackground(background(0x1affffff, 16));
        TextView tick = text("✓", 18, 0xff7ee0a8); tick.setTypeface(null, Typeface.BOLD); readyCard.addView(tick);
        LinearLayout readyText = column(); TextView readyTitle = text("Ready to play", 15, Color.WHITE); readyTitle.setTypeface(null, Typeface.BOLD); readyText.addView(readyTitle);
        readyRelease = text(TtrUpdater.release(activity), 12, MUTED); readyText.addView(readyRelease);
        LinearLayout.LayoutParams readyParams = new LinearLayout.LayoutParams(0, -2, 1); readyParams.leftMargin = dp(12); readyCard.addView(readyText, readyParams);
        add(mainPanel, readyCard, 14);
        // TTR Mobile's own version and whether a newer release is out (Windows app only: TtrAppUpdate exists there).
        TextView appVersion = text("", 12, MUTED); appVersion.setGravity(Gravity.CENTER); add(mainPanel, appVersion, 12);
        try { Class.forName("org.ttrmobile.TtrAppUpdate").getMethod("attach", TextView.class, int.class, int.class).invoke(null, appVersion, MUTED, GOLD); }
        catch (Exception e) { appVersion.setVisibility(View.GONE); }

        // Account page.
        accountPanel = column(); add(card, accountPanel, 18); accountPanel.setVisibility(View.GONE);
        accountTitle = text("Add an account", 19, Color.WHITE); accountTitle.setTypeface(null, Typeface.BOLD); accountPanel.addView(accountTitle);
        accountHint = text("", 13, MUTED); add(accountPanel, accountHint, 4);
        username = field("Username or email", false); password = field("Password", true); add(accountPanel, username, 14); add(accountPanel, password, 8);
        // Password managers may fill the account form (the verification code field stays excluded).
        username.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES); username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES); password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        passwordHelp = text("", 12, MUTED); add(accountPanel, passwordHelp, 6);
        save = button("Save account"); save.setTextColor(INK); save.setTypeface(null, Typeface.BOLD); save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        save.setOnClickListener(v -> saveAccount()); add(accountPanel, save, 12);
        // Saved account: playing is the main action here too; it returns to the main page, where the launch shows.
        accountPlay = button("Play"); accountPlay.setTextColor(INK); accountPlay.setTypeface(null, Typeface.BOLD); accountPlay.setTextSize(17);
        accountPlay.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        accountPlay.setOnClickListener(v -> { showPanel(MAIN); play(); });
        LinearLayout.LayoutParams accountPlayParams = new LinearLayout.LayoutParams(-1, dp(58)); accountPlayParams.topMargin = dp(12); accountPanel.addView(accountPlay, accountPlayParams);
        verify = button("Check login"); verify.setOnClickListener(v -> { hideKeyboard(); TtrRuntime.lastError = null; TtrLauncher.verify(activity, TtrLauncher.selected); }); add(accountPanel, verify, 4);
        changePassword = button("Change password"); changePassword.setOnClickListener(v -> { editingPassword = !editingPassword; password.setText(""); if (editingPassword) password.requestFocus(); else hideKeyboard(); changed(); }); add(accountPanel, changePassword, 4);
        // Destructive: the standard solid red with bold white text, same shape as the buttons above.
        forget = button("Remove from this phone"); forget.setTextColor(Color.WHITE); forget.setTypeface(null, Typeface.BOLD);
        forget.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffd93a3a));
        forget.setOnClickListener(v -> forgetAccount()); add(accountPanel, forget, 14);
        backFooter(accountPanel, this::leaveAccountPage);

        // Settings page: short sections; each setting's explanation opens from its (i).
        gamePanel = column(); add(card, gamePanel, 18); gamePanel.setVisibility(View.GONE);
        TextView settingsTitle = text("Settings", 19, Color.WHITE); settingsTitle.setTypeface(null, Typeface.BOLD); gamePanel.addView(settingsTitle);
        add(gamePanel, text("Game settings apply the next time the game starts.", 12, MUTED), 2);
        section("Display");
        add(gamePanel, setting("Resolution", null, null), 10);
        int pixels = TtrResolution.get(activity);
        add(gamePanel, choices(new String[]{"540p", "720p", "1080p"}, new String[]{"Smoothest", null, "Sharpest"}, 1, pixels == 540 ? 0 : pixels == 1080 ? 2 : 1,
            i -> TtrResolution.set(activity, new int[]{540, 720, 1080}[i])), 6);
        addGraphicsChoice();
        addSound();
        section("Controls");
        add(gamePanel, setting("Haptic feedback", "In the game, this works in portrait only.",
            toggle(TtrTouchControls.haptics(activity), on -> TtrTouchControls.setHaptics(activity, on))), 10);
        section("Content packs");
        add(gamePanel, setting("Content packs", "Packs change how the game looks or sounds. Add them from the browser here, or copy .mf files into the packs folder.", null), 10);
        Button managePacks = button("Manage content packs"); managePacks.setOnClickListener(v -> showPanel(PACKS)); add(gamePanel, managePacks, 8);
        section("Game files");
        release = text(TtrUpdater.release(activity), 12, MUTED); add(gamePanel, release, 6);
        storageBox = column(); storageBox.setPadding(dp(14), dp(12), dp(14), dp(12)); storageBox.setBackground(background(0x26ffffff, 14)); add(gamePanel, storageBox, 10);
        retry = button("Check and repair game files"); retry.setOnClickListener(v -> { TtrGameFiles.ensure(activity, true); showPanel(MAIN); }); add(gamePanel, retry, 8);
        section("Help");
        Button diagnostics = button("Share diagnostics"); diagnostics.setOnClickListener(v -> shareDiagnostics()); add(gamePanel, diagnostics, 8);
        addDetailedLogging();
        TextView about = text("TTR Mobile " + appBuild() + "\nUnofficial launcher; not made by the Toontown Rewritten team.", 12, MUTED); add(gamePanel, about, 12);
        about.setOnLongClickListener(v -> { TtrDiagnostics.show(activity); return true; });
        backFooter(gamePanel, () -> showPanel(MAIN));

        // Content packs page: the installed packs with their icon, name and description; on/off, order, delete.
        packsPanel = column(); add(card, packsPanel, 18); packsPanel.setVisibility(View.GONE);
        TextView packsTitle = text("Content packs", 19, Color.WHITE); packsTitle.setTypeface(null, Typeface.BOLD); packsPanel.addView(packsTitle);
        packsIntro = text("", 12, MUTED); add(packsPanel, packsIntro, 2);
        Button browse = button("Find and download packs"); browse.setTypeface(null, Typeface.BOLD); browse.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD)); browse.setTextColor(INK);
        browse.setOnClickListener(v -> TtrPackBrowser.open(activity)); add(packsPanel, browse, 14);
        packList = column(); add(packsPanel, packList, 12);
        add(packsPanel, text("Already have a pack? Copy its .mf file into the pack folder with a file manager (look for TTR Mobile in its side menu).", 12, MUTED), 14);
        Button openFolder = button("Open pack folder"); openFolder.setOnClickListener(v -> openPackFolder()); add(packsPanel, openFolder, 8);
        backFooter(packsPanel, this::leavePacks);

        // Live sign-in progress, shared by all pages (results and errors show as a notice instead).
        authStage = text("", 14, SOFT); authStage.setVisibility(View.GONE); add(card, authStage, 12);
        authProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); authProgress.setIndeterminate(true); add(card, authProgress, 2);
        cancelAuth = link("Cancel"); cancelAuth.setOnClickListener(v -> TtrLauncher.cancel(activity)); add(card, cancelAuth, 2);

        outer.addView(card, new LinearLayout.LayoutParams(cardWidth(), -2)); scroll.addView(outer); root.addView(scroll); view = root;
        notice = text("", 14, Color.WHITE); notice.setGravity(Gravity.CENTER); notice.setPadding(dp(18), dp(12), dp(18), dp(12));
        notice.setBackground(background(0xf2173344, 18)); notice.setElevation(dp(6)); notice.setVisibility(View.GONE); notice.setMaxWidth(cardWidth());
        notice.setOnClickListener(v -> hideNotice(false));
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); noticeParams.bottomMargin = dp(24);
        root.addView(notice, noticeParams);
        // Edge to edge: the artwork runs behind the system bars; only the card keeps clear of them and the keyboard.
        if (TtrSafeArea.edgeToEdge) scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else { l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop(); r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom(); }
            insetPad = new int[]{l, t, r, b};
            if (packsDone == null) outer.setPadding(dp(16) + l, dp(20) + t, dp(16) + r, dp(20) + b);
            FrameLayout.LayoutParams n = (FrameLayout.LayoutParams) notice.getLayoutParams(); n.bottomMargin = dp(24) + b; notice.setLayoutParams(n);
            return insets;
        });
        root.setFocusableInTouchMode(true); root.requestFocus();
    }
    /** A settings section heading (small gold capitals, like the launcher's cards). */
    private void section(String title) {
        TextView t = text(title, 12, GOLD); t.setTypeface(null, Typeface.BOLD); t.setAllCaps(true); t.setLetterSpacing(0.08f); add(gamePanel, t, 22);
    }
    /**
     * One setting: its name, an (i) that shows a short explanation when tapped (hidden until then), and an optional
     * control on the right (a switch).
     */
    private View setting(String label, String info, View trailing) {
        LinearLayout box = column(), row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(40));
        row.addView(text(label, 15, Color.WHITE));
        TextView more = text(info == null ? "" : info, 12, MUTED); more.setVisibility(View.GONE); more.setLineSpacing(0, 1.25f);
        if (info != null) {
            TextView icon = text("i", 12, SOFT); icon.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)); icon.setGravity(Gravity.CENTER);
            icon.setBackground(background(0x26ffffff, 11)); icon.setContentDescription("About " + label);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22)); ip.leftMargin = dp(8); row.addView(icon, ip);
            View.OnClickListener open = v -> more.setVisibility(more.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            icon.setOnClickListener(open); row.setOnClickListener(open);
        }
        row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        if (trailing != null) row.addView(trailing);
        box.addView(row); add(box, more, 2);
        return box;
    }
    /** An on/off switch in the launcher's colours. */
    private Switch toggle(boolean on, java.util.function.Consumer<Boolean> changed) {
        Switch s = new Switch(activity); s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{GOLD, 0xffb8d0dd}));
        s.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0x88ffd65e, 0x55ffffff}));
        s.setOnCheckedChangeListener((b, value) -> changed.accept(value));
        return s;
    }
    /** A dropdown: each choice has a name, an optional note, and the recommended one a gold tag. */
    private Spinner choices(String[] titles, String[] notes, int recommended, int selected, java.util.function.IntConsumer picked) {
        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        spinner.setAdapter(new BaseAdapter() {
            public int getCount() { return titles.length; }
            public Object getItem(int i) { return titles[i]; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) { return choice(i, false); }
            @Override public View getDropDownView(int i, View old, ViewGroup parent) { return choice(i, true); }
            private View choice(int i, boolean open) {
                LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(open ? 14 : 11), dp(14), dp(open ? 14 : 11));
                TextView name = text(titles[i], 15, Color.WHITE); row.addView(name);
                if (notes != null && notes[i] != null) { TextView note = text(notes[i], 13, MUTED); LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.leftMargin = dp(8); row.addView(note, np); }
                if (i == recommended) {
                    TextView tag = text("Recommended", 11, INK); tag.setTypeface(null, Typeface.BOLD); tag.setBackground(background(GOLD, 9)); tag.setPadding(dp(8), dp(2), dp(8), dp(3));
                    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = dp(10); row.addView(tag, tp);
                }
                if (!open) { row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1)); row.addView(text("▾", 15, MUTED)); }
                return row;
            }
        });
        spinner.setBackground(background(0xff173344, 12)); spinner.setPopupBackgroundDrawable(background(0xff173344, 12));
        spinner.setSelection(selected, false);
        final int[] last = {selected};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { if (position != last[0]) { last[0] = position; picked.accept(position); } }
        });
        return spinner;
    }
    /** Game volumes: they replace the game's own each time it starts (changes made in the game last until then). */
    private void addSound() {
        section("Sound");
        add(gamePanel, setting("Game volume", "These replace the volumes set inside the game.", null), 10);
        for (String[] v : TtrGameSettings.VOLUMES) {
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(v[1], 14, SOFT); row.addView(label, new LinearLayout.LayoutParams(dp(130), -2));
            SeekBar bar = new SeekBar(activity); bar.setMax(100); bar.setProgress(TtrGameSettings.volume(activity, v[0]));
            bar.setContentDescription(v[1] + " volume");
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD)); bar.setThumbTintList(android.content.res.ColorStateList.valueOf(GOLD));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff));
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(36), 1));
            TextView value = text(TtrGameSettings.volume(activity, v[0]) + "%", 13, MUTED); value.setGravity(Gravity.END); row.addView(value, new LinearLayout.LayoutParams(dp(44), -2));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar b, int progress, boolean user) { value.setText(progress + "%"); if (user) TtrGameSettings.audio(activity).edit().putInt(v[0], progress).apply(); }
                public void onStartTrackingTouch(SeekBar b) { }
                public void onStopTrackingTouch(SeekBar b) { }
            });
            add(gamePanel, row, 2);
        }
    }
    /** Detailed Windows logging for a problem that keeps happening (Windows app only: its runtime reads this setting). */
    private void addDetailedLogging() {
        try { Class.forName("org.ttrmobile.TtrRuntime").getDeclaredMethod("detailedLogging", android.content.Context.class); } catch (Exception e) { return; }
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ttr-debug", 0);
        add(gamePanel, setting("Detailed logging", "Records more detail for a problem that keeps happening.\nRestart TTR Mobile after turning it on, then share diagnostics.",
            toggle(prefs.getBoolean("detailed", false), on -> prefs.edit().putBoolean("detailed", on).commit())), 10);
    }
    /** Graphics path (Windows app only: TtrGraphics exists there; other builds show nothing). */
    private void addGraphicsChoice() {
        final java.lang.reflect.Method get, set;
        try {
            Class<?> g = Class.forName("org.ttrmobile.TtrGraphics");
            get = g.getDeclaredMethod("mode", android.content.Context.class); get.setAccessible(true);
            set = g.getDeclaredMethod("setMode", android.content.Context.class, String.class); set.setAccessible(true);
        } catch (Exception e) { return; }
        final String[] modes = {"auto", "vulkan", "compat"};
        add(gamePanel, setting("Graphics", "Automatic switches to Compatibility if Faster can't start the game.\nApplies after TTR Mobile restarts.", null), 14);
        int selected = 0; try { selected = Math.max(0, java.util.Arrays.asList(modes).indexOf((String) get.invoke(null, activity))); } catch (Exception ignored) { }
        add(gamePanel, choices(new String[]{"Automatic", "Faster", "Compatibility"}, new String[]{null, "Vulkan", "Works everywhere"}, 0, selected,
            i -> { try { set.invoke(null, activity, modes[i]); } catch (Exception ignored) { } }), 6);
    }
    private int cardWidth() { return Math.min(dp(480), activity.getResources().getDisplayMetrics().widthPixels - dp(32)); }
    private final Runnable noticeTimeout = () -> hideNotice(true);
    /** Shows a notice for a few seconds (longer for longer text); tapping it or changing page hides it sooner. */
    private void showNotice(CharSequence text) {
        if (text == null || text.length() == 0) return;
        ui.removeCallbacks(noticeTimeout); notice.animate().cancel();
        notice.setText(text); notice.setVisibility(View.VISIBLE); notice.setAlpha(0f); notice.setTranslationY(dp(8));
        notice.animate().alpha(1f).translationY(0).setDuration(180).start();
        ui.postDelayed(noticeTimeout, Math.min(10000, Math.max(4000, text.length() * 70)));
    }
    private void hideNotice(boolean fade) {
        ui.removeCallbacks(noticeTimeout); notice.animate().cancel();
        if (notice.getVisibility() != View.VISIBLE) return;
        if (!fade) { notice.setVisibility(View.GONE); return; }
        notice.animate().alpha(0f).setDuration(250).withEndAction(() -> notice.setVisibility(View.GONE)).start();
    }
    private void showPanel(int which) {
        if (which != ACCOUNT && panel == ACCOUNT) hideKeyboard();
        if (which != panel) hideNotice(false);
        panel = which;
        mainPanel.setVisibility(which == MAIN ? View.VISIBLE : View.GONE);
        accountPanel.setVisibility(which == ACCOUNT ? View.VISIBLE : View.GONE);
        gamePanel.setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        packsPanel.setVisibility(which == PACKS ? View.VISIBLE : View.GONE);
        if (which == PACKS) refreshPacks();
        if (which == SETTINGS) refreshStorage();
        changed();
    }
    /** Opens the system Files app at the pack folder (TtrPackProvider). */
    private void openPackFolder() {
        try {
            android.net.Uri folder = android.provider.DocumentsContract.buildRootUri(TtrPackProvider.authority(activity), TtrPackProvider.ROOT_ID);
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(folder, android.provider.DocumentsContract.Root.MIME_TYPE_ITEM));
        } catch (Exception e) { showNotice("No file manager found to open the pack folder."); }
    }
    private boolean packsLocked() {
        if (!TtrRuntime.isSessionRunning(activity)) return false;
        showNotice("The game is open: turn packs on or off and reorder them in its own Content Packs list."); return true;
    }
    /** Lists the installed packs (read off the main thread: each pack's name and icon come from inside its file). */
    private void refreshPacks() {
        final int load = ++packsLoad;
        new Thread(() -> {
            List<TtrPacks.Pack> packs = TtrPacks.list(activity);
            ui.post(() -> { if (load == packsLoad && panel == PACKS) showPacks(packs); });
        }, "ttr-packs").start();
    }
    /** Set while the manager is open over the running game: leaving it goes back to the game. */
    private Runnable packsDone;
    /** Over the running game the manager is the only page: no settings button, and Back returns to the game. */
    public void openPacksOverGame(Runnable done) { packsDone = done; gear.setVisibility(View.GONE); backdrop(false); showPanel(PACKS); }
    public void leavePacks() {
        Runnable done = packsDone; packsDone = null; gear.setVisibility(View.VISIBLE); backdrop(true);
        if (done != null) { showPanel(MAIN); done.run(); } else showPanel(SETTINGS);
    }
    private void showPacks(List<TtrPacks.Pack> packs) {
        packsIntro.setText(TtrRuntime.isSessionRunning(activity)
            ? "The game is open. Download packs here, then tap Refresh All in the game's Content Packs. Turn packs on or off and set their order in the game."
            : "Drag \u2261 to reorder: packs higher in the list win when two change the same thing. Changes apply the next time the game starts.");
        packList.removeAllViews();
        if (packs.isEmpty()) { TextView none = text("No content packs yet.", 14, SOFT); none.setPadding(dp(4), dp(4), dp(4), dp(4)); packList.addView(none); return; }
        for (int i = 0; i < packs.size(); i++) {
            TtrPacks.Pack pack = packs.get(i); int index = i;
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(12), dp(12), dp(12)); row.setBackground(background(0x26ffffff, 14));
            if (packs.size() > 1) row.addView(dragHandle(row, pack, index), new LinearLayout.LayoutParams(dp(30), dp(48)));
            row.addView(packIcon(pack), new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout words = column(); LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = dp(12); wp.rightMargin = dp(8); row.addView(words, wp);
            TextView name = text(pack.name, 15, Color.WHITE); name.setTypeface(null, Typeface.BOLD); words.addView(name);
            if (!pack.description.isEmpty()) { TextView description = text(pack.description, 12, MUTED); description.setLineSpacing(0, 1.15f); words.addView(description); }
            add(words, text(TtrPacks.readable(pack.size), 12, 0xff8fb0c0), 3);
            View trash = new Trash(); trash.setContentDescription("Delete " + pack.name); trash.setClickable(true); tactile(trash); trash.setOnClickListener(v -> deletePack(pack));
            row.addView(trash, new LinearLayout.LayoutParams(dp(40), dp(40)));
            row.addView(toggle(pack.enabled, on -> { if (packsLocked()) { refreshPacks(); return; } try { TtrPacks.setEnabled(activity, pack, on); } catch (Exception e) { showNotice("Unable to change this pack."); refreshPacks(); } }));
            add(packList, row, index == 0 ? 0 : 8);
        }
    }
    /**
     * The grip at the left of a pack: hold it and drag the pack up or down; the others make room, and letting go drops
     * it there. Near the top or bottom of the screen the page scrolls along.
     */
    private View dragHandle(LinearLayout row, TtrPacks.Pack pack, int from) {
        TextView grip = text("\u2261", 26, MUTED); grip.setGravity(Gravity.CENTER); grip.setContentDescription("Drag to reorder " + pack.name);
        final float[] startY = new float[1]; final int[] startScroll = new int[1], target = new int[1]; final float[] lastRaw = new float[1];
        final ScrollView[] scroller = new ScrollView[1];
        final Runnable[] follow = new Runnable[1];
        Runnable place = () -> {
            float dy = lastRaw[0] - startY[0] + (scroller[0] == null ? 0 : scroller[0].getScrollY() - startScroll[0]);
            row.setTranslationY(dy);
            float center = row.getTop() + row.getHeight() / 2f + dy; int to = 0;
            for (int j = 0; j < packList.getChildCount(); j++) { View other = packList.getChildAt(j); if (j != from && other.getTop() + other.getHeight() / 2f < center) to++; }
            target[0] = to; float shift = row.getHeight() + dp(8);
            for (int j = 0; j < packList.getChildCount(); j++) {
                if (j == from) continue; View other = packList.getChildAt(j);
                other.animate().translationY(j > from && j <= to ? -shift : j < from && j >= to ? shift : 0).setDuration(120).start();
            }
        };
        follow[0] = () -> {
            if (scroller[0] == null) return;
            int[] at = new int[2]; scroller[0].getLocationOnScreen(at); float top = at[1], bottom = at[1] + scroller[0].getHeight(), edge = dp(90);
            int step = lastRaw[0] < top + edge ? -dp(12) : lastRaw[0] > bottom - edge ? dp(12) : 0;
            if (step != 0) { scroller[0].scrollBy(0, step); place.run(); }
            ui.postDelayed(follow[0], 16);
        };
        grip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (packsLocked()) return true;
                    for (android.view.ViewParent p = packList.getParent(); p != null; p = p.getParent()) if (p instanceof ScrollView) { scroller[0] = (ScrollView) p; break; }
                    v.getParent().requestDisallowInterceptTouchEvent(true); if (scroller[0] != null) scroller[0].requestDisallowInterceptTouchEvent(true);
                    startY[0] = lastRaw[0] = e.getRawY(); startScroll[0] = scroller[0] == null ? 0 : scroller[0].getScrollY(); target[0] = from;
                    row.setElevation(dp(8)); row.setAlpha(.92f); row.setScaleX(1.02f); row.setScaleY(1.02f);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); ui.post(follow[0]); return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    lastRaw[0] = e.getRawY(); place.run(); return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(follow[0]); row.setElevation(0); row.setAlpha(1f); row.setScaleX(1f); row.setScaleY(1f);
                    for (int j = 0; j < packList.getChildCount(); j++) { packList.getChildAt(j).animate().cancel(); packList.getChildAt(j).setTranslationY(0); }
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && target[0] != from) {
                        try { TtrPacks.reorder(activity, pack, target[0]); } catch (Exception ex) { showNotice("Unable to change the order."); }
                    }
                    refreshPacks(); return true;
            }
            return false;
        });
        return grip;
    }
    private View packIcon(TtrPacks.Pack pack) {
        android.graphics.Bitmap bitmap = null;
        if (pack.icon != null) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeByteArray(pack.icon, 0, pack.icon.length, o);
            if (o.outWidth > 0 && o.outWidth <= 1024 && o.outHeight <= 1024) bitmap = android.graphics.BitmapFactory.decodeByteArray(pack.icon, 0, pack.icon.length);
        }
        if (bitmap != null) {
            ImageView image = new ImageView(activity); image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackground(background(0x33000000, 10)); image.setPadding(dp(2), dp(2), dp(2), dp(2));
            image.setContentDescription(pack.name + " icon"); return image;
        }
        TextView letter = text(pack.name.isEmpty() ? "?" : pack.name.substring(0, 1).toUpperCase(), 20, SOFT); letter.setGravity(Gravity.CENTER); letter.setTypeface(null, Typeface.BOLD); letter.setBackground(background(CHIP, 10)); return letter;
    }
    /** Delete icon: a bin drawn to scale, like the gear and pencil. */
    private final class Trash extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        Trash() { super(activity); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float s = Math.min(getWidth(), getHeight()), x = (getWidth() - s) / 2f, y = (getHeight() - s) / 2f;
            paint.setColor(0xffff8a8a); paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2f, s * .06f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND); paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            path.reset();
            path.moveTo(x + s * .24f, y + s * .32f); path.lineTo(x + s * .76f, y + s * .32f);                                   // lid
            path.moveTo(x + s * .42f, y + s * .32f); path.lineTo(x + s * .42f, y + s * .24f); path.lineTo(x + s * .58f, y + s * .24f); path.lineTo(x + s * .58f, y + s * .32f); // handle
            path.moveTo(x + s * .30f, y + s * .32f); path.lineTo(x + s * .34f, y + s * .78f); path.lineTo(x + s * .66f, y + s * .78f); path.lineTo(x + s * .70f, y + s * .32f); // bin
            path.moveTo(x + s * .43f, y + s * .46f); path.lineTo(x + s * .43f, y + s * .66f);
            path.moveTo(x + s * .57f, y + s * .46f); path.lineTo(x + s * .57f, y + s * .66f);
            c.drawPath(path, paint);
        }
    }
    private void deletePack(TtrPacks.Pack pack) {
        if (packsLocked()) return;
        new TtrDialog(activity, "Delete " + pack.name + "?", "This removes the pack (" + TtrPacks.readable(pack.size) + ") from this phone. You can download it again later.")
            .button("Delete", TtrDialog.DANGER, () -> { try { TtrPacks.delete(activity, pack); TtrPackProvider.changed(activity); } catch (Exception e) { showNotice("Unable to delete this pack."); } refreshPacks(); })
            .button("Cancel", TtrDialog.QUIET, null).show();
    }
    /** Windows and Linux builds share the report; older builds only have the on-screen log. */
    private void shareDiagnostics() {
        try { TtrDiagnostics.class.getMethod("share", Activity.class).invoke(null, activity); }
        catch (Exception e) { TtrDiagnostics.show(activity); }
    }
    /** Play waiting for the game update (tap again to cancel): "Updating" with dots that fill in one by one. */
    private CharSequence updating() {
        int n = (int) (android.os.SystemClock.uptimeMillis() / 400 % 4);
        android.text.SpannableString s = new android.text.SpannableString("Updating...");
        s.setSpan(new android.text.style.ForegroundColorSpan(Color.TRANSPARENT), 8 + n, 11, 0); // hidden dots keep the width steady
        return s;
    }
    private final Runnable dots = new Runnable() { public void run() { if (!playQueued || !resumed) return; play.setText(updating()); ui.postDelayed(this, 400); } };
    private void hideKeyboard() { ((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(), 0); password.clearFocus(); }
    private void refreshAccounts() {
        try {
            verifiedAccounts=vault.overview();names=new ArrayList<>(verifiedAccounts.keySet());
            if(!names.contains(TtrLauncher.selected)){
                String last=draftNew?"":vault.selected();
                TtrLauncher.selected=names.contains(last)?last:!draftNew&&names.size()==1?names.get(0):"";
            }
            accountButtons.clear();editIcons.clear();accountChoices.removeAllViews();
            for(String name:names){Button choice=button(name);chip(choice,CHIP);
                choice.setMinWidth(dp(130));choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choice.setPadding(dp(16),dp(6),dp(40),dp(6));
                choice.setOnClickListener(v->selectAccount(name));
                choice.setOnLongClickListener(v->{selectAccount(name);if(name.equals(TtrLauncher.selected))showPanel(ACCOUNT);return true;});
                accountButtons.put(name,choice);
                FrameLayout card=new FrameLayout(activity);card.addView(choice,new FrameLayout.LayoutParams(-2,-1));
                View edit=new Pencil();edit.setBackground(background(0x26000000,14));edit.setContentDescription("Edit "+name);edit.setClickable(true);
                edit.setOnClickListener(v->{if(!TtrLauncher.busy&&!TtrRuntime.isSessionRunning(activity))showPanel(ACCOUNT);});tactile(edit);
                FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP|Gravity.END);ep.topMargin=dp(6);ep.rightMargin=dp(6);card.addView(edit,ep);
                editIcons.put(name,edit);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(64));p.rightMargin=dp(8);accountChoices.addView(card,p);
            }
            accountChoices.addView(addAccount,new LinearLayout.LayoutParams(-2,dp(64)));
            username.setText(TtrLauncher.selected);password.setText("");updateAccountStatus();
            scrollToSelected();
            if(names.isEmpty())showPanel(ACCOUNT);
        } catch (Exception e) { showNotice("Unable to unlock saved accounts."); }
    }
    private void selectAccount(String name){
        if(TtrLauncher.busy||runtimeStarting||TtrRuntime.isSessionRunning(activity))return;
        TtrLauncher.selected=name;draftNew=false;draftUsername="";draftPassword="";editingPassword=false;
        username.setText(name);password.setText("");hideKeyboard();
        try{vault.select(name);}catch(Exception e){TtrLauncher.notice("Unable to remember this account selection.");}
        updateAccountStatus();scrollToSelected();changed();
    }
    private void scrollToSelected(){Button choice=accountButtons.get(TtrLauncher.selected);if(choice==null)return;View card=(View)choice.getParent();card.post(()->accountsScroll.smoothScrollTo(Math.max(0,card.getLeft()-dp(8)),0));}
    private void newAccount(){
        if(TtrLauncher.busy||runtimeStarting||TtrRuntime.isSessionRunning(activity))return;
        TtrLauncher.selected="";draftNew=true;draftUsername="";draftPassword="";editingPassword=false;
        username.setText("");password.setText("");showPanel(ACCOUNT);
        updateAccountStatus();changed();username.requestFocus();
    }
    private void updateAccountStatus() {
        try { verifiedAccounts=vault.overview();Long value=verifiedAccounts.get(TtrLauncher.selected);long time=value==null?0:value;
            accountStatus.setText(TtrLauncher.selected.isEmpty() ? (names.isEmpty() ? "Add your Toontown Rewritten account to get started." : "Tap an account to choose it.")
                : (time == 0 ? "Login not checked yet" : "Login checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time))));
            for(Map.Entry<String,Button> entry:accountButtons.entrySet()){Long verified=verifiedAccounts.get(entry.getKey());
                entry.getValue().setText(Html.fromHtml("<b>"+Html.escapeHtml(entry.getKey())+"</b><br><small>"+(verified!=null&&verified>0?"✓ Login checked":"Not checked yet")+"</small>",Html.FROM_HTML_MODE_LEGACY));}
        }
        catch (Exception e) { accountStatus.setText("Unable to read login status."); }
    }
    private void saveAccount() {
        if (TtrLauncher.busy) return;
        String name = username.getText().toString().trim(), secret = password.getText().toString();
        try {
            if (name.isEmpty() || name.length() > 254 || secret.length() > 4096) { showNotice("Enter your username and password."); return; }
            if (secret.isEmpty()) { showNotice("Enter the password for this account."); return; }
            vault.save(name, secret);vault.select(name); TtrLauncher.selected = name;editingPassword=false;draftNew=false;draftUsername="";draftPassword=""; password.setText(""); hideKeyboard(); refreshAccounts();
            TtrLauncher.notice("Account saved. Tap Check login to make sure it works, or go back and play."); changed();
        } catch (Exception e) { showNotice("Unable to save the account securely."); }
    }
    private void forgetAccount() {
        String selected = TtrLauncher.selected; if (selected.isEmpty() || TtrLauncher.busy) return;
        new TtrDialog(activity, "Remove " + selected + "?", "This only removes the saved login from this phone. Your Toontown account is not affected.")
            .button("Remove", TtrDialog.DANGER, () -> { try { vault.remove(selected); TtrLauncher.selected = "";editingPassword=false;draftNew=false; username.setText(""); password.setText(""); refreshAccounts(); if (!names.isEmpty()) showPanel(MAIN); TtrLauncher.notice("Account removed from this phone."); changed(); } catch (Exception e) { showNotice("Unable to remove this account."); } })
            .button("Cancel", TtrDialog.QUIET, null).show();
    }
    private void play() {
        if (TtrLauncher.busy || runtimeStarting) return;
        if (TtrRuntime.isSessionRunning(activity)) { TtrRuntime.resume(activity); return; }
        if (TtrLauncher.selected.isEmpty()) { newAccount(); return; }
        if (panel != MAIN) showPanel(MAIN);
        if (!TtrGameFiles.ready) { playQueued = !playQueued; changed(); return; }
        playQueued = false;
        if (!TtrStorage.granted(activity)) { if (waitingStorage) return; waitingStorage = true; try { TtrStorage.request(activity); } catch (Exception e) { waitingStorage = false; showNotice("Unable to open file-access settings."); } return; }
        TtrRuntime.lastError = null; TtrLauncher.play(activity, TtrLauncher.selected);
    }
    @Override public void changed() {
        if (!resumed || activity.isFinishing() || activity.isDestroyed()) return;
        // While signing in, its progress stays in the card; the outcome, and any Windows error, shows once as a notice.
        String message = TtrLauncher.message == null ? "" : TtrLauncher.message;
        authStage.setText(TtrLauncher.busy ? Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY) : "");
        authStage.setVisibility(TtrLauncher.busy && !message.isEmpty() ? View.VISIBLE : View.GONE);
        if (!TtrLauncher.busy && shownMessage != TtrLauncher.messageId) {
            shownMessage = TtrLauncher.messageId;
            if (!"Starting game…".equals(message)) showNotice(Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY)); // the launch card says it
        }
        String error = TtrRuntime.lastError;
        if (error != null && !error.equals(shownError)) showNotice(error);
        shownError = error;
        fileStage.setText(TtrGameFiles.message); release.setText(TtrGameFiles.release); readyRelease.setText(TtrGameFiles.release);
        String phase=TtrRuntime.phase(activity);
        boolean busy = TtrLauncher.busy || runtimeStarting || phase.equals("closing_game"), running = TtrRuntime.isSessionRunning(activity), selected = !TtrLauncher.selected.isEmpty();
        if (playQueued && (!selected || running)) playQueued = false;
        if (playQueued && TtrGameFiles.ready && !busy) { play(); return; }
        play.setText(phase.equals("closing_game") ? "Closing the game…" : running ? "Return to game" : playQueued ? updating() : selected ? "Play as " + TtrLauncher.selected
            : names.isEmpty() ? "Add an account to play" : "Choose an account");
        play.setEnabled(!busy && !editingPassword && (selected || names.isEmpty() || running));
        play.setAlpha(play.isEnabled() ? 1f : .55f);
        for(Map.Entry<String,Button> entry:accountButtons.entrySet()){
            boolean chosen=entry.getKey().equals(TtrLauncher.selected);Button b=entry.getValue();b.setEnabled(!busy&&!running);b.setSelected(chosen);
            b.setTextColor(chosen?INK:Color.WHITE);chip(b,chosen?GOLD:CHIP);
            View edit=editIcons.get(entry.getKey());if(edit!=null)edit.setVisibility(chosen&&!running?View.VISIBLE:View.GONE);
        }
        accountStatus.setVisibility(running || selected ? View.GONE : View.VISIBLE);
        addAccount.setEnabled(!busy&&!running);
        // Account page
        accountTitle.setText(selected ? TtrLauncher.selected : "Add an account");
        Long checked = verifiedAccounts.get(TtrLauncher.selected);
        accountHint.setText(selected ? "Saved on this phone  ·  " + (checked == null || checked == 0 ? "Login not checked yet" : "Login checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(checked))) : "Use your Toontown Rewritten username (or email) and password. They are stored encrypted on this phone only.");
        save.setEnabled(!busy);save.setVisibility(selected&&!editingPassword?View.GONE:View.VISIBLE);save.setText(selected?"Save new password":"Save account");
        changePassword.setVisibility(selected?View.VISIBLE:View.GONE);changePassword.setEnabled(!busy);changePassword.setText(editingPassword?"Keep current password":"Change password");
        verify.setVisibility(selected&&!editingPassword?View.VISIBLE:View.GONE);verify.setEnabled(!busy);
        forget.setVisibility(selected?View.VISIBLE:View.GONE);forget.setEnabled(!busy);
        accountPlay.setText(running ? "Return to game" : "Play as " + TtrLauncher.selected);
        accountPlay.setVisibility(selected && !editingPassword ? View.VISIBLE : View.GONE);
        accountPlay.setEnabled(!busy); accountPlay.setAlpha(accountPlay.isEnabled() ? 1f : .55f);
        username.setVisibility(selected?View.GONE:View.VISIBLE);username.setEnabled(!busy&&!selected);
        password.setVisibility(!selected||editingPassword?View.VISIBLE:View.GONE);password.setEnabled(!busy&&(!selected||editingPassword));
        password.setHint(selected?"New password":"Password");
        passwordHelp.setText(selected&&!editingPassword?"The password is saved. You can check that it still works, or change it.":selected?"Enter the new password for this account.":"");
        passwordHelp.setVisibility(passwordHelp.getText().length()==0?View.GONE:View.VISIBLE);
        // One status card on the main page: launch, then first-time setup, then a game download, else ready.
        TtrStartupProgress.Stage runtime=TtrStartupProgress.runtime(activity,TtrPlatform.RUNTIME,phase,TtrStorage.granted(activity));
        runtimePanel.show(runtime);
        // Steps 2-3 (storage optimisation, environment setup) happen once; starting Windows happens every time.
        runtimePanel.heading.setText(runtime.step == 2 || runtime.step == 3 ? "Setting up · first time only" : "Getting ready");
        TtrStartupProgress.Stage startup=TtrStartupProgress.client(activity,phase,TtrLauncher.busy&&TtrLauncher.playing,runtimeStarting||TtrLauncher.launchReady,runtime);
        clientPanel.show(startup);
        boolean launching = !startup.label.equals("Ready to play"), preparing = runtime.percent < 100, downloading = TtrGameFiles.busy || !TtrGameFiles.ready;
        clientPanel.root.setVisibility(launching ? View.VISIBLE : View.GONE);
        if (launching) authStage.setVisibility(View.GONE); // the launch card already says it
        ui.removeCallbacks(dots); if (playQueued) ui.postDelayed(dots, 400);
        runtimePanel.root.setVisibility(!launching && preparing ? View.VISIBLE : View.GONE);
        filesCard.setVisibility(!launching && !preparing && downloading ? View.VISIBLE : View.GONE);
        readyCard.setVisibility(!launching && !preparing && !downloading && !running ? View.VISIBLE : View.GONE);
        retry.setEnabled(!TtrGameFiles.busy && !running && !TtrLauncher.playing && !runtimeStarting); cancelFiles.setVisibility(TtrGameFiles.busy ? View.VISIBLE : View.GONE); fileProgress.setVisibility(TtrGameFiles.busy ? View.VISIBLE : View.GONE);
        fileProgress.setIndeterminate(TtrGameFiles.percent<0);if(TtrGameFiles.percent>=0)fileProgress.setProgress(TtrGameFiles.percent);
        authProgress.setVisibility(TtrLauncher.busy ? View.VISIBLE : View.GONE); cancelAuth.setVisibility(TtrLauncher.busy ? View.VISIBLE : View.GONE);
        if (previousBusy && !TtrLauncher.busy) updateAccountStatus(); previousBusy = TtrLauncher.busy;
        if (TtrLauncher.challenge != null && shownChallenge != TtrLauncher.challengeId) {
            shownChallenge = TtrLauncher.challengeId; if (codeDialog != null) codeDialog.dismiss(); EditText code = field("Verification code", false);
            codeDialog = new TtrDialog(activity, "Enter your verification code", Html.fromHtml(TtrLauncher.challenge, Html.FROM_HTML_MODE_LEGACY)).view(code)
                .buttonIf("Continue", TtrDialog.PRIMARY, () -> { if (code.length() == 0) { code.setError("Enter the verification code."); return false; } TtrLauncher.submit(code.getText().toString()); code.setText(""); return true; })
                .button("Cancel", TtrDialog.QUIET, () -> TtrLauncher.cancel(activity)).onCancel(() -> TtrLauncher.cancel(activity)).show();
            code.requestFocus(); codeDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        if (TtrLauncher.consumeLaunch()) {
            runtimeStarting = true; TtrDiagnostics.beginAttempt(activity);
            if (!TtrInitialization.ensure(activity)) { runtimeStarting = false; TtrSession.clear(activity); changed(); return; }
            TtrRuntime.begin(activity, () -> { if (TtrRuntime.lastError != null) TtrSession.clear(activity); changed(); });
        }
    }
    public void resume() {
        resumed = true; TtrLauncher.attach(this);
        if (!activity.getIntent().getBooleanExtra("ttr_test", false)) TtrGameFiles.ensure(activity, false);
        changed(); ui.post(watch); if (panel == PACKS) refreshPacks();
        if (waitingStorage) {
            waitingStorage = false;
            if (!TtrStorage.granted(activity)) showNotice("File access is needed to run the game.");
        }
        if (prewarm && !activity.getIntent().getBooleanExtra("ttr_test", false)) warmWindows();
    }
    private void warmWindows() {
        if (!TtrStorage.granted(activity)) {
            if (!waitingStorage) { waitingStorage = true; TtrStorage.request(activity); }
            return;
        }
        if (TtrInitialization.ensure(activity)) TtrRuntime.begin(activity, this::changed);
    }
    public void pause() {
        draftUsername = username.getText().toString(); draftPassword = password.getText().toString();
        resumed = false; ui.removeCallbacks(watch); TtrLauncher.detach(this);
        if (codeDialog != null) { codeDialog.dismiss(); codeDialog = null; shownChallenge = -1; }
    }
    /** Back from the account or settings page returns to the main page; false when already there. */
    public boolean back() { if (packsDone != null) { leavePacks(); return true; } if (panel == MAIN || view.getVisibility() != View.VISIBLE) return false; if (panel == ACCOUNT) leaveAccountPage(); else if (panel == PACKS) leavePacks(); else showPanel(MAIN); return true; }
    public void resize() {
        ViewGroup.LayoutParams p = card.getLayoutParams(); p.width = cardWidth(); card.setLayoutParams(p); notice.setMaxWidth(cardWidth());
    }
    public void destroy() { pause(); ui.removeCallbacks(noticeTimeout); username.setText(""); password.setText(""); }
    public void clientClosed() { packsDone = null; gear.setVisibility(View.VISIBLE); backdrop(true); runtimeStarting = false; draftPassword = ""; password.setText(""); }
}
