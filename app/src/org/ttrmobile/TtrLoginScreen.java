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
    private LinearLayout card, mainPanel, gamePanel, accountPanel, filesCard, readyCard;
    private TextView authStage, fileStage, release, readyRelease, accountStatus, passwordHelp, accountTitle, accountHint;
    private ProgressPanel runtimePanel, clientPanel;
    private LinearLayout accountChoices;
    private HorizontalScrollView accountsScroll;
    private Button addAccount, changePassword;
    private final Map<String,Button> accountButtons=new LinkedHashMap<>();
    private Map<String,Long> verifiedAccounts=new LinkedHashMap<>();
    private Button play, accountPlay, save, verify, forget, retry, cancelAuth, cancelFiles;
    private ProgressBar fileProgress, authProgress;
    private static final int MAIN = 0, ACCOUNT = 1, SETTINGS = 2;
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
    private AlertDialog codeDialog;
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
            if (TtrRuntime.lastError != null) { authStage.setText(TtrRuntime.lastError); runtimeStarting = false; }
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
    /** Account chips: flat rounded cards (no button shadow), so the row lines up. */
    private void chip(Button b, int color) { b.setBackgroundTintList(null); b.setBackground(background(color, 14)); b.setStateListAnimator(null); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private String appBuild() { try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xff25485a);
        ImageView art = new ImageView(activity); art.setImageDrawable(artwork("background.png")); art.setScaleType(ImageView.ScaleType.CENTER_CROP); root.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity); shade.setBackgroundColor(0x850b1b25); root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        LinearLayout outer = new LinearLayout(activity); outer.setGravity(Gravity.CENTER); outer.setPadding(dp(16), dp(20), dp(16), dp(20));
        card = column(); card.setPadding(dp(22), dp(20), dp(22), dp(16)); card.setBackground(background(0xef102b3a, 22));

        // Header: game icon and name, settings on the right.
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity); icon.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager())); icon.setContentDescription("TTR Mobile (unofficial)"); header.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = column();
        TextView title = text("Toontown Rewritten", 22, Color.WHITE); title.setTypeface(null, Typeface.BOLD); titles.addView(title);
        titles.addView(text("Android wrapper for Windows TTR", 13, MUTED));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = dp(12); header.addView(titles, titleParams);
        View gear = new Gear(); gear.setContentDescription("Settings"); gear.setClickable(true); gear.setFocusable(true); tactile(gear);
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
        accountStatus = text("", 13, MUTED); accountStatus.setPadding(0, dp(2), 0, dp(2)); add(mainPanel, accountStatus, 6);
        accountStatus.setOnClickListener(v -> { if (!TtrLauncher.selected.isEmpty()) showPanel(ACCOUNT); });
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
        forget = link("Remove from this phone"); forget.setTextColor(0xffff9c8f); forget.setOnClickListener(v -> forgetAccount()); add(accountPanel, forget, 4);
        Button accountDone = link("‹  Back"); accountDone.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        accountDone.setOnClickListener(v -> { editingPassword = false; draftNew = false; if (TtrLauncher.selected.isEmpty() && !names.isEmpty()) refreshAccounts(); showPanel(MAIN); }); add(accountPanel, accountDone, 4);

        // Settings page.
        gamePanel = column(); add(card, gamePanel, 18); gamePanel.setVisibility(View.GONE);
        TextView settingsTitle = text("Settings", 19, Color.WHITE); settingsTitle.setTypeface(null, Typeface.BOLD); gamePanel.addView(settingsTitle);
        add(gamePanel, heading("Game resolution"), 16);
        add(gamePanel, text("Higher looks sharper; lower runs smoother on older phones. Applies the next time the game starts.", 12, MUTED), 2);
        Spinner resolution = new Spinner(activity);
        ArrayAdapter<String> choices = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"540p · smoothest", "720p · recommended", "1080p · sharpest"});
        resolution.setAdapter(choices); int pixels = TtrResolution.get(activity); resolution.setSelection(pixels == 540 ? 0 : pixels == 1080 ? 2 : 1);
        resolution.setBackground(background(0xff173344, 10));
        resolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { if (v instanceof TextView) ((TextView) v).setTextColor(Color.WHITE); TtrResolution.set(activity, new int[]{540, 720, 1080}[position]); }
        }); add(gamePanel, resolution, 8);
        addGraphicsChoice();
        add(gamePanel, heading("Controls"), 18);
        Switch vibrate = new Switch(activity); vibrate.setText("Haptic feedback"); vibrate.setTextColor(Color.WHITE); vibrate.setTextSize(15);
        vibrate.setChecked(TtrTouchControls.haptics(activity)); vibrate.setOnCheckedChangeListener((b, on) -> TtrTouchControls.setHaptics(activity, on));
        add(gamePanel, vibrate, 6);
        add(gamePanel, text("A short vibration when you press a button. In the game it applies in portrait only.", 12, MUTED), 0);
        add(gamePanel, heading("Game files"), 18);
        release = text(TtrUpdater.release(activity), 12, MUTED); add(gamePanel, release, 2);
        retry = button("Check and repair game files"); retry.setOnClickListener(v -> { TtrGameFiles.ensure(activity, true); showPanel(MAIN); }); add(gamePanel, retry, 8);
        add(gamePanel, heading("Help"), 18);
        Button diagnostics = button("Share diagnostics"); diagnostics.setOnClickListener(v -> shareDiagnostics()); add(gamePanel, diagnostics, 8);
        TextView about = text("TTR Mobile " + appBuild() + "\nUnofficial launcher; not made by the Toontown Rewritten team.", 12, MUTED); add(gamePanel, about, 12);
        about.setOnLongClickListener(v -> { TtrDiagnostics.show(activity); return true; });
        Button settingsDone = link("‹  Back"); settingsDone.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); settingsDone.setOnClickListener(v -> showPanel(MAIN)); add(gamePanel, settingsDone, 4);

        // Messages from sign-in and launch, shared by all pages.
        authStage = text(TtrLauncher.message, 14, SOFT); add(card, authStage, 12);
        authProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); authProgress.setIndeterminate(true); add(card, authProgress, 2);
        cancelAuth = link("Cancel"); cancelAuth.setOnClickListener(v -> TtrLauncher.cancel(activity)); add(card, cancelAuth, 2);

        outer.addView(card, new LinearLayout.LayoutParams(cardWidth(), -2)); scroll.addView(outer); root.addView(scroll); view = root;
        // Edge to edge: the artwork runs behind the system bars; only the card keeps clear of them and the keyboard.
        if (TtrSafeArea.edgeToEdge) scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else { l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop(); r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom(); }
            outer.setPadding(dp(16) + l, dp(20) + t, dp(16) + r, dp(20) + b);
            return insets;
        });
        root.setFocusableInTouchMode(true); root.requestFocus();
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
        add(gamePanel, heading("Graphics"), 18);
        add(gamePanel, text("Faster uses Vulkan; Compatibility works on every phone but runs slower. Automatic tries the faster one "
            + "and switches if the game can't start with it. Applies the next time TTR Mobile starts.", 12, MUTED), 2);
        Spinner graphics = new Spinner(activity);
        graphics.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Automatic · recommended", "Faster (Vulkan)", "Compatibility"}));
        graphics.setBackground(background(0xff173344, 10));
        try { graphics.setSelection(java.util.Arrays.asList(modes).indexOf((String) get.invoke(null, activity))); } catch (Exception ignored) { }
        graphics.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (v instanceof TextView) ((TextView) v).setTextColor(Color.WHITE);
                if (first) { first = false; return; }
                try { set.invoke(null, activity, modes[position]); } catch (Exception ignored) { }
            }
        });
        add(gamePanel, graphics, 8);
    }
    private int cardWidth() { return Math.min(dp(480), activity.getResources().getDisplayMetrics().widthPixels - dp(32)); }
    private void showPanel(int which) {
        if (which != ACCOUNT && panel == ACCOUNT) hideKeyboard();
        panel = which;
        mainPanel.setVisibility(which == MAIN ? View.VISIBLE : View.GONE);
        accountPanel.setVisibility(which == ACCOUNT ? View.VISIBLE : View.GONE);
        gamePanel.setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        changed();
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
            accountButtons.clear();accountChoices.removeAllViews();
            for(String name:names){Button choice=button(name);chip(choice,CHIP);
                choice.setMinWidth(dp(130));choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choice.setPadding(dp(16),dp(6),dp(16),dp(6));
                choice.setOnClickListener(v->selectAccount(name));
                choice.setOnLongClickListener(v->{selectAccount(name);if(name.equals(TtrLauncher.selected))showPanel(ACCOUNT);return true;});
                accountButtons.put(name,choice);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(64));p.rightMargin=dp(8);accountChoices.addView(choice,p);
            }
            accountChoices.addView(addAccount,new LinearLayout.LayoutParams(-2,dp(64)));
            username.setText(TtrLauncher.selected);password.setText("");updateAccountStatus();
            scrollToSelected();
            if(names.isEmpty())showPanel(ACCOUNT);
        } catch (Exception e) { authStage.setText("Unable to unlock saved accounts."); }
    }
    private void selectAccount(String name){
        if(TtrLauncher.busy||runtimeStarting||TtrRuntime.isSessionRunning(activity))return;
        TtrLauncher.selected=name;draftNew=false;draftUsername="";draftPassword="";editingPassword=false;
        username.setText(name);password.setText("");hideKeyboard();
        try{vault.select(name);}catch(Exception e){TtrLauncher.message="Unable to remember this account selection.";}
        updateAccountStatus();scrollToSelected();changed();
    }
    private void scrollToSelected(){Button choice=accountButtons.get(TtrLauncher.selected);if(choice!=null)choice.post(()->accountsScroll.smoothScrollTo(Math.max(0,choice.getLeft()-dp(8)),0));}
    private void newAccount(){
        if(TtrLauncher.busy||runtimeStarting||TtrRuntime.isSessionRunning(activity))return;
        TtrLauncher.selected="";draftNew=true;draftUsername="";draftPassword="";editingPassword=false;
        username.setText("");password.setText("");showPanel(ACCOUNT);
        updateAccountStatus();changed();username.requestFocus();
    }
    private void updateAccountStatus() {
        try { verifiedAccounts=vault.overview();Long value=verifiedAccounts.get(TtrLauncher.selected);long time=value==null?0:value;
            accountStatus.setText(TtrLauncher.selected.isEmpty() ? (names.isEmpty() ? "Add your Toontown Rewritten account to get started." : "Tap an account to choose it.")
                : (time == 0 ? "Login not checked yet" : "Login checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time))) + "  ·  Manage");
            for(Map.Entry<String,Button> entry:accountButtons.entrySet()){Long verified=verifiedAccounts.get(entry.getKey());
                entry.getValue().setText(Html.fromHtml("<b>"+Html.escapeHtml(entry.getKey())+"</b><br><small>"+(verified!=null&&verified>0?"✓ Login checked":"Not checked yet")+"</small>",Html.FROM_HTML_MODE_LEGACY));}
        }
        catch (Exception e) { accountStatus.setText("Unable to read login status."); }
    }
    private void saveAccount() {
        if (TtrLauncher.busy) return;
        String name = username.getText().toString().trim(), secret = password.getText().toString();
        try {
            if (name.isEmpty() || name.length() > 254 || secret.length() > 4096) { authStage.setText("Enter your username and password."); return; }
            if (secret.isEmpty()) { authStage.setText("Enter the password for this account."); return; }
            vault.save(name, secret);vault.select(name); TtrLauncher.selected = name;editingPassword=false;draftNew=false;draftUsername="";draftPassword=""; password.setText(""); hideKeyboard(); refreshAccounts();
            TtrLauncher.message = "Account saved. Tap Check login to make sure it works, or go back and play."; changed();
        } catch (Exception e) { authStage.setText("Unable to save the account securely."); }
    }
    private void forgetAccount() {
        String selected = TtrLauncher.selected; if (selected.isEmpty() || TtrLauncher.busy) return;
        new AlertDialog.Builder(activity).setTitle("Remove " + selected + "?").setMessage("This only removes the saved login from this phone. Your Toontown account is not affected.")
            .setPositiveButton("Remove", (d, w) -> { try { vault.remove(selected); TtrLauncher.selected = "";editingPassword=false;draftNew=false; username.setText(""); password.setText(""); refreshAccounts(); TtrLauncher.message = "Account removed from this phone."; if (!names.isEmpty()) showPanel(MAIN); changed(); } catch (Exception e) { authStage.setText("Unable to remove this account."); } })
            .setNegativeButton("Cancel", null).show();
    }
    private void play() {
        if (TtrLauncher.busy || runtimeStarting) return;
        if (TtrRuntime.isSessionRunning(activity)) { TtrRuntime.resume(activity); return; }
        if (TtrLauncher.selected.isEmpty()) { newAccount(); return; }
        if (panel != MAIN) showPanel(MAIN);
        if (!TtrGameFiles.ready) { playQueued = !playQueued; changed(); return; }
        playQueued = false;
        if (!TtrStorage.granted(activity)) { if (waitingStorage) return; waitingStorage = true; try { TtrStorage.request(activity); } catch (Exception e) { waitingStorage = false; authStage.setText("Unable to open file-access settings."); } return; }
        TtrRuntime.lastError = null; TtrLauncher.play(activity, TtrLauncher.selected);
    }
    @Override public void changed() {
        if (!resumed || activity.isFinishing() || activity.isDestroyed()) return;
        if(TtrRuntime.lastError!=null)authStage.setText(TtrRuntime.lastError);
        else authStage.setText(Html.fromHtml(TtrLauncher.message == null ? "" : TtrLauncher.message, Html.FROM_HTML_MODE_LEGACY));
        authStage.setVisibility(authStage.getText().length() == 0 ? View.GONE : View.VISIBLE);
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
        }
        accountStatus.setVisibility(running ? View.GONE : View.VISIBLE);
        addAccount.setEnabled(!busy&&!running);
        // Account page
        accountTitle.setText(selected ? TtrLauncher.selected : "Add an account");
        accountHint.setText(selected ? "Saved on this phone." : "Use your Toontown Rewritten username (or email) and password. They are stored encrypted on this phone only.");
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
        if (launching && TtrRuntime.lastError == null) authStage.setVisibility(View.GONE); // the card already says it
        // The login's last step leaves "Starting game…" behind; once the launch is over it no longer applies.
        if (!launching && !TtrLauncher.busy && "Starting game…".equals(TtrLauncher.message)) { TtrLauncher.message = ""; authStage.setText(""); authStage.setVisibility(View.GONE); }
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
            codeDialog = new AlertDialog.Builder(activity).setTitle("Enter your verification code").setMessage(Html.fromHtml(TtrLauncher.challenge, Html.FROM_HTML_MODE_LEGACY)).setView(code)
                .setPositiveButton("Continue", null).setNegativeButton("Cancel", (d,w) -> TtrLauncher.cancel(activity)).create();
            codeDialog.setOnCancelListener(d -> TtrLauncher.cancel(activity)); codeDialog.show();
            codeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { if (code.length() == 0) { code.setError("Enter the verification code."); return; } TtrLauncher.submit(code.getText().toString()); code.setText(""); codeDialog.dismiss(); });
            code.requestFocus(); codeDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        if (TtrLauncher.consumeLaunch()) {
            runtimeStarting = true; TtrDiagnostics.beginAttempt(activity);
            if (!TtrInitialization.ensure(activity)) { runtimeStarting = false; authStage.setText(TtrRuntime.lastError); TtrSession.clear(activity); return; }
            TtrRuntime.begin(activity, () -> { changed(); if (TtrRuntime.lastError != null) { TtrSession.clear(activity); authStage.setText(TtrRuntime.lastError); } });
        }
    }
    public void resume() {
        resumed = true; TtrLauncher.attach(this);
        if (!activity.getIntent().getBooleanExtra("ttr_test", false)) TtrGameFiles.ensure(activity, false);
        changed(); ui.post(watch);
        if (waitingStorage) {
            waitingStorage = false;
            if (!TtrStorage.granted(activity)) authStage.setText("File access is needed to run the game.");
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
    public boolean back() { if (panel == MAIN || view.getVisibility() != View.VISIBLE) return false; editingPassword = false; draftNew = false; showPanel(MAIN); return true; }
    public void resize() {
        ViewGroup.LayoutParams p = card.getLayoutParams(); p.width = cardWidth(); card.setLayoutParams(p);
    }
    public void destroy() { pause(); username.setText(""); password.setText(""); }
    public void clientClosed() { runtimeStarting = false; draftPassword = ""; password.setText(""); }
}
