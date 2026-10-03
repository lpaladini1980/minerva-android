package it.paladia.minerva;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings, filled once: the Argo credentials, as in the DidUp app. */
public class SettingsActivity extends Activity {
    private EditText school, username, password;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setPadding(0, dp(12), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);  // before setInputType: setSingleLine() replaces the password mask
        e.setInputType(type);
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        e.setText(value);
        parent.addView(e);
        return e;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Settings s = Settings.load(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(form);

        TextView title = new TextView(this);
        title.setText("Impostazioni di Minerva");
        title.setTextSize(22);
        form.addView(title);
        TextView note = new TextView(this);
        note.setText("Le stesse credenziali dell'app DidUp Famiglia. La password resta cifrata su questo telefono. "
                + "Minerva legge soltanto: non firma comunicazioni, non giustifica assenze, non scarica allegati.");
        note.setTextSize(13);
        note.setPadding(0, dp(8), 0, 0);
        form.addView(note);

        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        int secret = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        school = field(form, "Codice scuola (es. SC12345)", s.school, text | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        username = field(form, "Utente", s.username, text);
        password = field(form, "Password", s.password, secret);

        Button save = new Button(this);
        save.setText("Salva");
        save.setOnClickListener(v -> {
            read().save(this);
            finish();
        });
        form.addView(save);
        setContentView(scroll);
        MainActivity.darkStatusIcons(this);
    }

    private Settings read() {
        Settings s = new Settings();
        s.school = school.getText().toString().trim();
        s.username = username.getText().toString().trim();
        s.password = password.getText().toString();
        return s;
    }
}
