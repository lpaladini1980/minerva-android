package it.paladia.minerva;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.paladia.minerva.core.Argo;
import it.paladia.minerva.core.Cards;
import it.paladia.minerva.core.Http;

/**
 * Minerva: one dashboard per child (swipe or tap the name), pull down to refresh. No AI: it
 * shows the register only. Read-only on Argo (Argo.READ_ONLY_CALLS). School data lives only in memory, never on disk.
 * UI built in code, no XML layouts, no AndroidX (the app is built without Gradle).
 */
public class MainActivity extends Activity {
    private static final int[] COLORS = {0xFF00897B, 0xFFEF6C00, 0xFF5E35B1, 0xFF1E88E5};
    private static final long STALE_MS = 10 * 60 * 1000;

    /** In-memory data for the whole process: survives the activity being recreated, never written to disk. */
    static final class Data {
        static List<Argo.Profile> profiles = new ArrayList<>();
        static Map<String, Map<String, Object>> dashboards = new HashMap<>();
        static long loadedAt;
        static int selected;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout tabs;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView status;
    private boolean loading;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int color() {
        return COLORS[Math.max(0, Data.selected) % COLORS.length];
    }

    /** Dark clock and icons in the status bar: the app's background is light (Android 15+ draws edge to edge). */
    static void darkStatusIcons(Activity a) {
        WindowInsetsController c = a.getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F4F5);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(8), dp(6));
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabScroll.addView(tabs);
        header.addView(tabScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = new Button(this);
        refresh.setText("↻");
        refresh.setOnClickListener(v -> load(true));
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        status = text("", 13, 0xFF607D8B, false);
        status.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(status);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        attachGestures();

        setContentView(root);
        darkStatusIcons(this);  // after setContentView: the insets controller needs the window decor
    }

    @Override
    protected void onResume() {
        super.onResume();
        Settings s = Settings.load(this);
        if (!s.complete()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        render();
        load(System.currentTimeMillis() - Data.loadedAt > STALE_MS);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // --- loading ----------------------------------------------------------------------------------------------------

    private void load(boolean force) {
        if (loading || (!force && !Data.profiles.isEmpty())) return;
        Settings s = Settings.load(this);
        if (!s.complete()) return;
        loading = true;
        status.setText("Aggiorno dal registro…");
        worker.execute(() -> {
            try {
                Argo argo = new Argo(s.school, s.username, s.password, Http.DEFAULT);
                List<Argo.Profile> profiles = argo.login();
                profiles.sort(Comparator.comparing(Argo.Profile::displayName));
                Map<String, Map<String, Object>> dashboards = new HashMap<>();
                for (Argo.Profile p : profiles) dashboards.put(p.token, argo.dashboard(p));
                runOnUiThread(() -> {
                    Data.profiles = profiles;
                    Data.dashboards = dashboards;
                    Data.loadedAt = System.currentTimeMillis();
                    if (Data.selected >= profiles.size()) Data.selected = 0;
                    loading = false;
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading = false;
                    status.setText("Registro non raggiungibile: " + e.getMessage());
                });
            }
        });
    }

    // --- dashboard --------------------------------------------------------------------------------------------------

    private void render() {
        tabs.removeAllViews();
        for (int i = 0; i < Data.profiles.size(); i++) {
            final int index = i;
            TextView tab = text(Data.profiles.get(i).displayName(), 18, Color.WHITE, true);
            boolean on = i == Data.selected;
            int c = COLORS[i % COLORS.length];
            tab.setTextColor(on ? Color.WHITE : c);
            tab.setBackground(rounded(on ? c : 0x00000000, dp(20)));
            tab.setPadding(dp(16), dp(8), dp(16), dp(8));
            tab.setOnClickListener(v -> select(index));
            tabs.addView(tab);
        }
        content.removeAllViews();
        if (Data.profiles.isEmpty()) {
            if (!loading) status.setText("Tira giù o premi ↻ per caricare il registro.");
            return;
        }
        Argo.Profile p = Data.profiles.get(Data.selected);
        if (!loading) status.setText("Aggiornato alle " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALIAN)
                .format(new java.util.Date(Data.loadedAt)) + " · solo lettura");
        for (Cards.Card card : Cards.build(Data.dashboards.get(p.token), LocalDate.now())) content.addView(cardView(card));
        scroll.scrollTo(0, 0);
    }

    private void select(int index) {
        if (Data.profiles.isEmpty()) return;
        Data.selected = (index + Data.profiles.size()) % Data.profiles.size();
        render();
    }

    private View cardView(Cards.Card card) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.WHITE, dp(14)));
        box.setPadding(dp(14), dp(12), dp(14), dp(10));
        box.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(lp);

        box.addView(text(card.title, 17, color(), true));
        if (card.rows.isEmpty()) {
            TextView none = text(card.empty, 15, 0xFF90A4AE, false);
            none.setPadding(0, dp(6), 0, dp(2));
            box.addView(none);
        }
        for (Cards.Row r : card.rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(r.alert ? dp(8) : 0, dp(8), 0, dp(2));
            if (r.alert) row.setBackground(rounded(0x14E53935, dp(8)));
            StringBuilder head = new StringBuilder();
            if (!r.day.isEmpty()) head.append(r.day);
            if (!r.label.isEmpty()) head.append(head.length() > 0 ? " · " : "").append(r.label);
            if (head.length() > 0) row.addView(text(head.toString(), 13, r.alert ? 0xFFC62828 : 0xFF546E7A, true));
            row.addView(text(r.main, 15, 0xFF263238, false));
            if (!r.detail.isEmpty()) row.addView(text(r.detail, 13, 0xFF78909C, false));
            box.addView(row);
        }
        return box;
    }

    /** Swipe left or right to change daughter; pull down from the top to refresh. */
    private void attachGestures() {
        GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > dp(80) && Math.abs(dx) > 2 * Math.abs(dy)) {
                    select(Data.selected + (dx < 0 ? 1 : -1));
                    return true;
                }
                return false;
            }
        });
        final float[] downY = {-1};
        scroll.setOnTouchListener((v, e) -> {
            swipe.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_DOWN) downY[0] = scroll.getScrollY() == 0 ? e.getY() : -1;
            if (e.getAction() == MotionEvent.ACTION_UP && downY[0] >= 0 && e.getY() - downY[0] > dp(120) && scroll.getScrollY() == 0) {
                load(true);
            }
            return false;
        });
    }
}
