package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.preference.EditTextPreference;

import com.scamera.app.R;
import com.scamera.app.util.ConfigFolder;
import com.scamera.app.util.Lang;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * «Сохранить настройки»: asks for a file name (a dated one prefilled) for the config XML in {@link ConfigFolder}. The name
 * takes letters, digits and '_' only; anything else typed or pasted is dropped with a note. Stores nothing itself.
 */
public class BackupPreferences extends EditTextPreference {
    public BackupPreferences(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setPersistent(false);
        if (getSummary() == null) setSummary(ConfigFolder.RELATIVE); // SIMPLE_UI §4: the XML gives «Все настройки в один файл»
        setDialogMessage(Lang.t(context, "Все настройки и профили модулей сохраняются в XML-файл в папке ",
                "All settings and module profiles are saved to an XML file in the folder ") + ConfigFolder.RELATIVE);
        // the library's text dialog in the app's look (DialogStyle): the message and the app's field, aligned with the title
        setDialogLayoutResource(R.layout.dialog_preference_edittext);
        setOnBindEditTextListener(field -> {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            field.setText(getContext().getString(R.string.backup_file_name, stamp));
            field.setFilters(new InputFilter[]{this::fileNameOnly});
        });
    }

    boolean isAcceptedCharacter(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private void refuse(char c) {
        Toast.makeText(getContext(), Lang.t(getContext(), "Недопустимый символ «" + c + "»", "Invalid character «" + c + "»"),
                Toast.LENGTH_SHORT).show();
    }

    /** The input with the refused characters taken out (an editable span is edited in place, as the field expects). */
    private CharSequence fileNameOnly(CharSequence in, int start, int end, Spanned dest, int dstart, int dend) {
        if (in instanceof SpannableStringBuilder) {
            SpannableStringBuilder editable = (SpannableStringBuilder) in;
            for (int i = end - 1; i >= start; i--) {
                char c = in.charAt(i);
                if (isAcceptedCharacter(c)) continue;
                refuse(c);
                editable.delete(i, i + 1);
            }
            return in;
        }
        StringBuilder kept = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = in.charAt(i);
            if (isAcceptedCharacter(c)) kept.append(c);
            else refuse(c);
        }
        return kept.toString();
    }
}
