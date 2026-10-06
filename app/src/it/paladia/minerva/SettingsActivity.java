package it.paladia.minerva;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import it.paladia.minerva.core.Classroom;
import it.paladia.minerva.core.Http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.List;

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
        addClassroomTrial(form);
        setContentView(scroll);
        MainActivity.darkStatusIcons(this);
    }

    /** Spike for #4: signs in with a child's school account and lists their courses, to see whether the school allows Minerva. Nothing is saved. */
    private void addClassroomTrial(LinearLayout form) {
        TextView note = new TextView(this);
        note.setText("Google Classroom (prova): accedi con l'account della scuola di tuo figlio per vedere se la scuola "
                + "permette a Minerva di leggere i corsi. Non viene salvato nulla.");
        note.setTextSize(13);
        note.setPadding(0, dp(24), 0, 0);
        form.addView(note);
        Button button = new Button(this);
        button.setText("Prova l'accesso a Classroom");
        form.addView(button);
        TextView result = new TextView(this);
        result.setTextSize(15);
        form.addView(result);
        button.setOnClickListener(v -> {
            if (!Classroom.configured()) {
                result.setText("Il collegamento a Google non è ancora configurato in questa versione di Minerva.");
                return;
            }
            button.setEnabled(false);
            result.setText("Accedi nel browser con l'account della scuola, poi torna qui.");
            new Thread(() -> {
                String text = classroomTrial();
                runOnUiThread(() -> {
                    result.setText(text);
                    button.setEnabled(true);
                });
            }).start();
        });
    }

    private String classroomTrial() {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5 * 60 * 1000);
            String redirect = "http://127.0.0.1:" + server.getLocalPort();
            String verifier = Classroom.newVerifier();
            String state = Classroom.newState();
            Uri url = Uri.parse(Classroom.authorizationUrl(redirect, verifier, state));
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, url)));
            String code = Classroom.awaitCode(server, state);
            Classroom classroom = new Classroom(Http.DEFAULT);
            classroom.exchangeCode(code, verifier, redirect);
            List<Classroom.Course> courses = classroom.courses();
            StringBuilder b = new StringBuilder("Accesso riuscito: " + courses.size() + (courses.size() == 1 ? " corso attivo." : " corsi attivi."));
            for (Classroom.Course c : courses) b.append("\n· ").append(c.name);
            return b.toString();
        } catch (SocketTimeoutException e) {
            return "Tempo scaduto. Se Google ha scritto «Accesso bloccato», la scuola non permette ancora Minerva.";
        } catch (IOException e) {
            return "Non riuscito: " + e.getMessage();
        }
    }

    private Settings read() {
        Settings s = new Settings();
        s.school = school.getText().toString().trim();
        s.username = username.getText().toString().trim();
        s.password = password.getText().toString();
        return s;
    }
}
